package aQute.launcher;

import static aQute.launcher.constants.LauncherConstants.CANCELED;
import static aQute.launcher.constants.LauncherConstants.DEFAULT_LAUNCHER_PROPERTIES;
import static aQute.launcher.constants.LauncherConstants.ERROR;
import static aQute.launcher.constants.LauncherConstants.UPDATE_NEEDED;
import static aQute.launcher.constants.LauncherConstants.WARNING;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Policy;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.service.permissionadmin.PermissionInfo;

import aQute.launcher.agent.LauncherAgent;
import aQute.launcher.constants.LauncherConstants;
import aQute.launcher.minifw.MiniFramework;
import aQute.lib.io.IO;
import aQute.lib.strings.Strings;

/**
 * This is the primary bnd launcher. It implements a launcher that runs on Java
 * 1.4.
 */
public class Launcher implements ServiceListener {

	// Use our own constant for this rather than depend on OSGi core 4.3
	private static final String FRAMEWORK_SYSTEM_CAPABILITIES_EXTRA = "org.osgi.framework.system.capabilities.extra";

	private PrintStream					out;
	LauncherConstants					parms;
	Framework							systemBundle;
	volatile boolean					inrefresh;
	private final Properties			properties;
	private boolean						security;
	private SimplePermissionPolicy		policy;
	private Callable<Integer>			mainThread;
	private final List<BundleActivator>	embedded			= new ArrayList<BundleActivator>();
	private final Map<Bundle,Throwable>	errors				= new HashMap<Bundle,Throwable>();
	private final Map<File,Bundle>		installedBundles	= new LinkedHashMap<File,Bundle>();
	private File						home				= new File(System.getProperty("user.home"));
	private File						bnd					= new File(home, "bnd");
	private List<Bundle>				wantsToBeStarted	= new ArrayList<Bundle>();
	AtomicBoolean						active				= new AtomicBoolean();

	private AtomicReference<DatagramSocket> commsSocket = new AtomicReference<DatagramSocket>();
	private PackageAdmin					padmin;

	public static void main(String[] args) {
		try {
			int exitcode = 0;
			try {
				final InputStream in;
				final File propertiesFile;

				String path = System.getProperty(LauncherConstants.LAUNCHER_PROPERTIES);
				if (path != null) {
					Matcher matcher = Pattern.compile("^([\"'])(.*)\\1$").matcher(path);
					if (matcher.matches()) {
						path = matcher.group(2);
					}

					propertiesFile = new File(path).getAbsoluteFile();
					if (!propertiesFile.isFile())
						errorAndExit("Specified launch file `%s' was not found - absolutePath='%s'", path,
								propertiesFile.getAbsolutePath());
					in = IO.stream(propertiesFile);
				} else {
					propertiesFile = null;
					in = Launcher.class.getClassLoader().getResourceAsStream(DEFAULT_LAUNCHER_PROPERTIES);
					if (in == null) {
						printUsage();
						errorAndExit("Launch file not specified, and no embedded properties found.");
						return;
					}
				}

				Properties properties = new Properties();
				load(in, properties);

				augmentWithSystemProperties(properties);

				Launcher target = new Launcher(properties, propertiesFile);
				exitcode = target.run(args);
			} catch (Throwable t) {
				exitcode = 127;
				// Last resort ... errors should be handled lower
				t.printStackTrace(System.err);
			}

			// We exit, even if there are non-daemon threads active
			// though we've reported those
			System.exit(exitcode);
		} finally {
			System.out.println("gone");
		}
	}

	private static void augmentWithSystemProperties(Properties properties) {
		for (String key : LauncherConstants.LAUNCHER_PROPERTY_KEYS) {
			String value = System.getProperty(key);
			if (value == null)
				continue;

			properties.put(key, value);
		}
	}

	static void load(final InputStream in, Properties properties) throws UnsupportedEncodingException, IOException {
		try (InputStreamReader ir = new InputStreamReader(in, "UTF-8")) {
			properties.load(ir);
		}
	}

	private static String getVersion() {
		try {
			Enumeration<URL> manifests = Launcher.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
			StringBuilder sb = new StringBuilder();
			String del = "";
			for (Enumeration<URL> u = manifests; u.hasMoreElements();) {
				URL url = u.nextElement();
				try (InputStream in = url.openStream()) {
					Manifest m = new Manifest(in);
					String bsn = m.getMainAttributes().getValue(Constants.BUNDLE_SYMBOLICNAME);
					String version = m.getMainAttributes().getValue(Constants.BUNDLE_VERSION);
					if (bsn != null && version != null) {
						sb.append(del).append(bsn).append(";version=").append(version);
						del = ", ";
					}
				}
			}
			return sb.toString();
		} catch (Exception e) {
			return "Cannot read manifest: " + e;
		}
	}

	private static void printUsage() {
		System.out.println("Usage: java -Dlauncher.properties=<launcher.properties> -jar <launcher.jar>");
	}

	private static void errorAndExit(String message, Object... args) {
		System.err.println(Strings.format(message, args));
		System.exit(ERROR);
	}

	public static int main(String[] args, Properties p) throws Throwable {
		Launcher target = new Launcher(p, null);
		return target.run(args);
	}

	public Launcher(Properties properties, final File propertiesFile) throws Exception {
		this.properties = properties;

		// Allow the system to override any properties with -Dkey=value

		for (Object key : properties.keySet()) {
			String s = (String) key;
			String v = System.getProperty(s);
			if (v != null)
				properties.put(key, v);
		}

		System.getProperties().putAll(properties);

		this.parms = new LauncherConstants(properties);
		out = System.err;

		setupComms();

		trace("properties " + properties);
		trace("inited runbundles=%s activators=%s timeout=%s properties=%s", parms.runbundles, parms.activators,
				parms.timeout);

		if (propertiesFile != null && parms.embedded == false) {
			TimerTask watchdog = new TimerTask() {
				long begin = propertiesFile.lastModified();

				public void run() {
					long now = propertiesFile.lastModified();
					if (begin < now) {
						try (InputStream in = IO.stream(propertiesFile)) {
							Properties properties = new Properties();
							load(in, properties);
							parms = new LauncherConstants(properties);
							update(now);
						} catch (Exception e) {
							error("Error in updating the framework from the properties: %s", e);
						}
						begin = now;
					}
				}
			};
			new Timer(true).scheduleAtFixedRate(watchdog, 5000, 1000);
		}
	}

	private void setupComms() {
		DatagramSocket oldSocket;
		if (parms.notificationPort == -1) {
			oldSocket = commsSocket.getAndSet(null);
		} else {
			oldSocket = commsSocket.get();
			if (oldSocket != null && oldSocket.getPort() == parms.notificationPort) {
				oldSocket = null;
			} else {
				DatagramSocket newSocket;
				try {
					newSocket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(null), 0));
					newSocket.connect(new InetSocketAddress(InetAddress.getByName(null), parms.notificationPort));

				} catch (IOException ioe) {
					// TODO what now?
					newSocket = null;
				}
				commsSocket.compareAndSet(oldSocket, newSocket);
			}
		}

		if (oldSocket != null) {
			oldSocket.close();
		}
	}

	private int run(String args[]) throws Throwable {
		try {
			trace("version %s", getVersion());

			int status = activate();
			if (status != 0) {
				report(out);
				System.exit(status);
			}

			trace("framework=" + systemBundle);

			// Register the command line with ourselves as the
			// service.
			if (parms.services) { // Does not work for our dummy framework

				try {
					if (LauncherAgent.instrumentation != null) {
						Hashtable<String,Object> argprops = new Hashtable<String,Object>();
						if (LauncherAgent.agentArgs != null)
							argprops.put("agent.arguments", LauncherAgent.agentArgs);
						systemBundle.getBundleContext().registerService(Instrumentation.class.getName(),
								LauncherAgent.instrumentation, argprops);
					}
				} catch (NoClassDefFoundError e) {
					// Must be running on a profile which does not support
					// java.lang.instrument
				}

				Hashtable<String,Object> argprops = new Hashtable<String,Object>();
				argprops.put(LauncherConstants.LAUNCHER_ARGUMENTS, args);
				argprops.put(LauncherConstants.LAUNCHER_READY, "true");
				argprops.put(Constants.SERVICE_RANKING, -1000);
				systemBundle.getBundleContext().registerService(new String[] {
						Object.class.getName(), Launcher.class.getName()
				}, this, argprops);
				trace("registered launcher with arguments for syncing");
			}

			// Wait until a Runnable is registered with main.thread=true.
			// not that this will never happen when we're running on the mini fw
			// but the test case normally exits.
			synchronized (this) {
				while (mainThread == null) {
					trace("will wait for a registered Runnable");
					wait();
				}
			}
			trace("will call main");
			Integer exitCode = mainThread.call();
			trace("main return, code " + exitCode);
			return exitCode == null ? 0 : exitCode;
		} catch (Throwable e) {
			error("Unexpected error in the run body: %s", e);
			throw e;
		} finally {
			deactivate();
			trace("stopped system bundle due to leaving run body");
			// TODO should we wait here?
		}
	}

	private List<String> split(String value, String separator) {
		List<String> list = new ArrayList<String>();
		if (value == null)
			return list;

		StringTokenizer st = new StringTokenizer(value, separator);
		while (st.hasMoreTokens())
			list.add(st.nextToken());

		return list;
	}

	public int activate() throws Exception {
		active.set(true);
		Policy.setPolicy(new AllPolicy());

		systemBundle = createFramework();
		if (systemBundle == null)
			return LauncherConstants.ERROR;

		doTimeoutHandler();

		doSecurity();

		// Initialize this framework so it becomes STARTING
		systemBundle.start();
		trace("system bundle started ok");

		BundleContext systemContext = systemBundle.getBundleContext();
		ServiceReference ref = systemContext.getServiceReference(PackageAdmin.class.getName());
		if (ref != null) {
			padmin = (PackageAdmin) systemContext.getService(ref);
		} else
			trace("could not get package admin");

		systemContext.addServiceListener(this, "(&(|(objectclass=" + Runnable.class.getName() + ")(objectclass="
				+ Callable.class.getName() + "))(main.thread=true))");

		int result = LauncherConstants.OK;

		// Start embedded activators
		trace("start embedded activators");
		if (parms.activators != null) {
			ClassLoader loader = getClass().getClassLoader();
			for (Object token : parms.activators) {
				try {
					Class< ? > clazz = loader.loadClass((String) token);
					BundleActivator activator = (BundleActivator) clazz.getConstructor().newInstance();
					if (isImmediate(activator)) {
						start(systemContext, result, activator);
					}
					embedded.add(activator);
					trace("adding activator %s", activator);
				} catch (Exception e) {
					throw new IllegalArgumentException("Embedded Bundle Activator incorrect: " + token, e);
				}
			}
		}

		update(System.currentTimeMillis() + 100);

		if (parms.trace) {
			report(out);
		}

		for (BundleActivator activator : embedded)
			if (!isImmediate(activator))
				result = start(systemContext, result, activator);

		return result;
	}

	private boolean isImmediate(BundleActivator activator) {
		try {
			Field f = activator.getClass().getField("IMMEDIATE");

			return f.getBoolean(activator);
		} catch (Exception e) {
			return false;
		}
	}

	private int start(BundleContext systemContext, int result, BundleActivator activator) {
		try {
			trace("starting activator %s", activator);
			activator.start(systemContext);
		} catch (Exception e) {
			error("Starting activator %s : %s", activator, e);
			result = LauncherConstants.ERROR;
		}
		return result;
	}

	/**
	 * Ensure that all the bundles in the parameters are actually started. We
	 * can start in embedded mode (bundles are inside our main jar) or in file
	 * system mode.
	 * 
	 * @param begin
	 */
	void update(long before) throws Exception {

		trace("Updating framework with %s", parms.runbundles);
		List<Bundle> tobestarted = new ArrayList<Bundle>();
		if (parms.embedded)
			installEmbedded(tobestarted);
		else
			synchronizeFiles(tobestarted, before);

		if (padmin != null) {
			inrefresh = true;
			padmin.refreshPackages(null);
			trace("Waiting for refresh to finish");

			// Will be reset by the Framework listener we added
			// when we created the framework.
			while (inrefresh)
				Thread.sleep(100);

		} else
			trace("cannot refresh the bundles because there is no Package Admin");

		trace("bundles administered %s", installedBundles.keySet());

		// From now on, the bundles are on their own. They have
		// by default AllPermission, but if they install bundles
		// they will not automatically get AllPermission anymore
		if (security)
			policy.setDefaultPermissions(null);

		// Get the resolved status
		if (padmin != null && padmin.resolveBundles(null) == false) {
			List<String> failed = new ArrayList<String>();

			for (Bundle b : installedBundles.values()) {
				try {
					if (b.getState() == Bundle.INSTALLED) {
						b.start();
					}
				} catch (Exception e) {
					failed.add(b.getSymbolicName() + "-" + b.getVersion() + " " + e + "\n");
				}
			}
			error("could not resolve the bundles: " + failed);
			// return LauncherConstants.RESOLVE_ERROR;
		}

		// Now start all the installed bundles in the same order
		// (unless they're a fragment)

		trace("Will start bundles: %s", tobestarted);
		List<Bundle> all = new ArrayList<Bundle>(tobestarted);
		// Add all bundles that we've tried to start but failed
		all.addAll(wantsToBeStarted);

		for (Bundle b : tobestarted) {
			try {
				trace("starting %s", b.getSymbolicName());
				if (!isFragment(b))
					b.start(Bundle.START_ACTIVATION_POLICY);
				trace("started  %s", b.getSymbolicName());
			} catch (BundleException e) {
				wantsToBeStarted.add(b);
				error("Failed to start bundle %s-%s, exception %s", b.getSymbolicName(), b.getVersion(), e);
			}
		}

	}

	/**
	 * @param tobestarted
	 */
	void synchronizeFiles(List<Bundle> tobestarted, long before) {
		// Turn the bundle location paths into files
		List<File> desired = new ArrayList<File>();

		for (Object o : parms.runbundles) {
			String s = (String) o;
			s = toNativePath(s);
			File file = new File(s).getAbsoluteFile();
			desired.add(file);
		}

		// deleted = old - new
		List<File> tobedeleted = new ArrayList<File>(installedBundles.keySet());

		tobedeleted.removeAll(desired);

		// updated = old /\ new
		List<File> tobeupdated = new ArrayList<File>(installedBundles.keySet());
		tobeupdated.retainAll(desired);

		// install = new - old
		List<File> tobeinstalled = new ArrayList<File>(desired);
		tobeinstalled.removeAll(installedBundles.keySet());

		for (File f : tobedeleted)
			try {
				trace("uninstalling %s", f);
				installedBundles.get(f).uninstall();
				installedBundles.remove(f);
			} catch (Exception e) {
				error("Failed to uninstall bundle %s, exception %s", f, e);
			}

		for (File f : tobeinstalled)
			try {
				trace("installing %s", f);
				if (f.exists()) {
					Bundle b = install(f);
					installedBundles.put(f, b);
					tobestarted.add(b);
				} else
					error("should installing %s but file does not exist", f);
			} catch (Exception e) {
				error("Failed to uninstall bundle %s, exception %s", f, e);
			}

		for (File f : tobeupdated)
			try {
				if (f.exists()) {
					Bundle b = installedBundles.get(f);

					//
					// Ensure we only update bundles that
					// we're modified before the properties file was modified.
					// Otherwise we might update bundles that are still being
					// written by bnd
					//
					if (f.lastModified() <= before) {
						if (b.getLastModified() < f.lastModified()) {
							trace("updating %s", f);
							if (b.getState() == Bundle.ACTIVE) {
								tobestarted.add(b);
								b.stop();
							}
							b.update();
						} else
							trace("bundle is still current according to timestamp %s", f);
					}
				} else
					error("should update %s but file does not exist", f);
			} catch (Exception e) {
				error("Failed to update bundle %s, exception %s", f, e);
			}
	}

	/**
	 * Convert a path to native when it contains a macro. This is needed for the
	 * jpm option since it stores the paths with a macro in the JAR through the
	 * packager. This path is platform independent and must therefore be
	 * translated to the executing platform. if no macro is present, we assume
	 * the path is already native.
	 * 
	 * @param s
	 */
	private String toNativePath(String s) {
		if (!s.contains("${"))
			return s;

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '/' :
					sb.append(File.separator);
					break;

				case '$' :
					if (s.length() - 3 > i) {
						char rover = s.charAt(++i);
						if (rover == '{') {
							rover = s.charAt(++i);
							StringBuilder var = new StringBuilder();
							while (i < s.length() - 1 && rover != '}') {
								var.append(rover);
								rover = s.charAt(++i);
							}
							String key = var.toString();
							String value = System.getProperty(key);
							if (value == null)
								value = System.getenv(key);
							if (value != null)
								sb.append(value);
							else
								sb.append("${").append(key).append("}");
						} else
							sb.append('$').append(rover);
					} else
						sb.append('$');
					break;

				case '\\' :
					if (s.length() - 1 > i)
						sb.append(s.charAt(++i));
					break;

				default :
					sb.append(c);
			}
		}
		return sb.toString();
	}

	/**
	 * Install/Update the bundles from the current jar.
	 * 
	 * @param tobestarted
	 * @throws BundleException
	 * @throws IOException
	 */
	void installEmbedded(List<Bundle> tobestarted) throws BundleException, IOException {
		trace("starting in embedded mode");
		BundleContext context = systemBundle.getBundleContext();
		for (Object o : parms.runbundles) {
			String path = (String) o;
			trace("installing %s", path);
			try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
				Bundle bundle = getBundleByLocation(path);
				if (bundle == null)
					bundle = context.installBundle(path, in);
				else
					bundle.update(in);
				tobestarted.add(bundle);
			}
		}
	}

	Bundle install(File f) throws Exception {
		BundleContext context = systemBundle.getBundleContext();
		try {
			String reference;
			if (isWindows() || parms.noreferences) {
				trace("no reference: url %s", parms.noreferences);
				reference = f.toURI().toURL().toExternalForm();
			} else
				reference = "reference:" + f.toURI().toURL().toExternalForm();

			Bundle b = context.installBundle(reference);
			if (b.getLastModified() < f.lastModified()) {
				b.update();
			}
			return b;
		} catch (BundleException e) {
			trace("failed reference, will try to install %s with input stream", f.getAbsolutePath());
			String reference = f.toURI().toURL().toExternalForm();
			try (InputStream in = IO.stream(f)) {
				return context.installBundle(reference, in);
			}
		}
	}

	private boolean isWindows() {
		return File.separatorChar == '\\';
	}

	private void doTimeoutHandler() {
		// Ensure we properly close in a separate thread so that
		// we can leverage the main thread, which is required for macs
		Thread wait = new Thread("FrameworkWaiter") {
			@Override
			public void run() {
				try {
					FrameworkEvent result = systemBundle.waitForStop(parms.timeout);
					if (!active.get()) {
						trace("ignoring timeout handler because framework is already no longer active, shutdown is orderly handled");
						return;
					}

					trace("framework event " + result + " " + result.getType());
					switch (result.getType()) {
						case FrameworkEvent.STOPPED :
							trace("framework event stopped");
							System.exit(LauncherConstants.STOPPED);
							break;

						case FrameworkEvent.WAIT_TIMEDOUT :
							trace("framework event timedout");
							System.exit(LauncherConstants.TIMEDOUT);
							break;

						case FrameworkEvent.ERROR :
							System.exit(ERROR);
							break;

						case FrameworkEvent.WARNING :
							System.exit(WARNING);
							break;

						case FrameworkEvent.STOPPED_BOOTCLASSPATH_MODIFIED :
						case FrameworkEvent.STOPPED_UPDATE :
							trace("framework event update");
							System.exit(UPDATE_NEEDED);
							break;
					}
				} catch (InterruptedException e) {
					System.exit(CANCELED);
				}
			}
		};
		wait.start();
	}

	private void doSecurity() {
		try {
			PermissionInfo allPermissions[] = new PermissionInfo[] {
					new PermissionInfo(AllPermission.class.getName(), null, null)
			};
			policy = new SimplePermissionPolicy(this, systemBundle.getBundleContext());

			// All bundles installed from the script are getting AllPermission
			// for now.
			policy.setDefaultPermissions(allPermissions);
			security = true;
		} catch (Throwable t) {
			// This can throw a linkage error when the framework
			// does not carry the PermissionAdmin class
			security = false;
		}
	}

	private boolean isFragment(Bundle b) {
		return padmin != null
				&& padmin.getBundleType(b) == PackageAdmin.BUNDLE_TYPE_FRAGMENT;
	}

	public void deactivate() throws Exception {
		if (active.getAndSet(false)) {
			systemBundle.stop();
			systemBundle.waitForStop(parms.timeout);

			ThreadGroup group = Thread.currentThread().getThreadGroup();
			Thread[] threads = new Thread[group.activeCount() + 100];
			group.enumerate(threads);
			{
				for (Thread t : threads) {
					if (t != null && !t.isDaemon() && t.isAlive()) {
						trace("alive thread " + t);
					}
				}
			}
		} else
			errorAndExit("Huh? Already deactivated.");
	}

	public void addSystemPackage(String packageName) {
		parms.systemPackages = concat(parms.systemPackages, packageName);
	}

	private String concat(String a, String b) {
		if (a == null)
			return b;
		else if (b == null)
			return a;

		return a + "," + b;
	}

	private Framework createFramework() throws Exception {
		Properties p = new Properties();
		p.putAll(properties);
		File workingdir = null;
		if (parms.storageDir != null)
			workingdir = parms.storageDir;
		else if (parms.keep && parms.name != null) {
			workingdir = new File(bnd, parms.name);
		}

		if (workingdir == null) {
			workingdir = File.createTempFile("osgi.", ".fw");
			final File wd = workingdir;
			Runtime.getRuntime().addShutdownHook(new Thread("launcher::delete temp working dir") {
				public void run() {
					deleteFiles(wd);
				}
			});
		}

		trace("using working dir: %s with keeping=%s", workingdir, parms.keep);

		if (!parms.keep && workingdir.exists()) {
			trace("deleting working dir %s because not kept", workingdir);
			delete(workingdir);
			p.setProperty(Constants.FRAMEWORK_STORAGE_CLEAN, "true");
		}

		if (!workingdir.exists() && !workingdir.mkdirs()) {
			throw new IOException("Could not create directory " + workingdir);
		}
		if (!workingdir.isDirectory())
			throw new IllegalArgumentException("Cannot create a working dir: " + workingdir);

		if (System.getProperty(Constants.FRAMEWORK_STORAGE) == null)
			p.setProperty(Constants.FRAMEWORK_STORAGE, workingdir.getAbsolutePath());
		else
			p.setProperty(Constants.FRAMEWORK_STORAGE, System.getProperty(Constants.FRAMEWORK_STORAGE));

		if (parms.systemPackages != null) {
			p.setProperty(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA, parms.systemPackages);
			trace("system packages used: %s", parms.systemPackages);
		}

		if (parms.systemCapabilities != null) {
			p.setProperty(FRAMEWORK_SYSTEM_CAPABILITIES_EXTRA, parms.systemCapabilities);
			trace("system capabilities used: %s", parms.systemCapabilities);
		}

		Framework systemBundle;

		if (parms.services) {
			trace("using META-INF/services");
			// 3) framework = null, lookup in META-INF/services

			ClassLoader loader = getClass().getClassLoader();

			// 3) Lookup in META-INF/services
			List<String> implementations = getMetaInfServices(loader, FrameworkFactory.class.getName());

			if (implementations.size() == 0)
				error("Found no fw implementation");
			if (implementations.size() > 1)
				error("Found more than one framework implementations: %s", implementations);

			String implementation = implementations.get(0);

			Class< ? > clazz = loader.loadClass(implementation);
			FrameworkFactory factory = (FrameworkFactory) clazz.getConstructor().newInstance();
			trace("Framework factory %s", factory);
			@SuppressWarnings({
					"unchecked", "rawtypes"
			})
			Map<String,String> configuration = (Map) p;
			systemBundle = factory.newFramework(configuration);
			trace("framework instance %s", systemBundle);
		} else {
			trace("using embedded mini framework because we were told not to use META-INF/services");
			// we have to use our own dummy framework
			systemBundle = new MiniFramework(p);
		}
		systemBundle.init();

		try {
			systemBundle.getBundleContext().addFrameworkListener(new FrameworkListener() {

				public void frameworkEvent(FrameworkEvent event) {
					switch (event.getType()) {
						case FrameworkEvent.ERROR :
						case FrameworkEvent.WAIT_TIMEDOUT :
							trace("Refresh will end due to error or timeout %s", event.toString());

						case FrameworkEvent.PACKAGES_REFRESHED :
							inrefresh = false;
							trace("refresh ended");
							break;
					}
				}
			});
		} catch (Exception e) {
			trace("could not register a framework listener: %s", e);
		}
		trace("inited system bundle %s", systemBundle);
		return systemBundle;
	}

	protected void deleteFiles(File wd) {
		if (wd.isDirectory()) {
			for (File sub : wd.listFiles()) {
				deleteFiles(sub);
			}
		}
		wd.delete();
	}

	/**
	 * Try to get the stupid service interface ...
	 * 
	 * @param loader
	 * @param string
	 * @throws IOException
	 */
	private List<String> getMetaInfServices(ClassLoader loader, String factory) throws IOException {
		if (loader == null)
			loader = getClass().getClassLoader();

		Enumeration<URL> e = loader.getResources("META-INF/services/" + factory);
		List<String> factories = new ArrayList<String>();

		while (e.hasMoreElements()) {
			URL url = e.nextElement();
			trace("found META-INF/services in %s", url);

			try (InputStream in = url.openStream();
					BufferedReader rdr = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
				String line;
				while ((line = rdr.readLine()) != null) {
					trace(line);
					line = line.trim();
					if (!line.startsWith("#") && line.length() > 0) {
						factories.add(line);
					}
				}
			}
		}
		return factories;
	}

	public void addBundle(File resource) {
		parms.runbundles.add(resource.getAbsolutePath());
	}

	private void delete(File f) {
		String path = f.getAbsolutePath();
		char first = path.charAt(0);
		if (path.equals("/") || (first >= 'A' && first <= 'Z' && path.substring(1).equals(":\\")))
			throw new IllegalArgumentException("You can not make the root the storage area because it will be deleted");
		if (f.isDirectory()) {
			File fs[] = f.listFiles();
			for (int i = 0; i < fs.length; i++)
				delete(fs[i]);
		}
		f.delete();
	}

	public void report(PrintStream out) {
		try {
			out.println("------------------------------- REPORT --------------------------");
			out.println();
			row(out, "Framework", systemBundle == null ? "<>" : systemBundle.getClass());
			row(out, "Framework type", parms.services ? "META-INF/services" : "mini framework");
			row(out, "Storage", parms.storageDir);
			row(out, "Keep", parms.keep);
			row(out, "Security", security);
			list(out, fill("Run bundles", 40), parms.runbundles);
			list(out, fill("Classpath", 40), split(System.getProperty("java.class.path"), File.pathSeparator));
			list(out, fill("System Packages", 40), split(parms.systemPackages, ","));
			list(out, fill("System Capabilities", 40), split(parms.systemCapabilities, ","));
			row(out, "Properties");
			for (Entry<Object,Object> entry : properties.entrySet()) {
				String key = (String) entry.getKey();
				String value = (String) entry.getValue();
				row(out, key, value);
			}
			if (systemBundle != null) {
				BundleContext context = systemBundle.getBundleContext();
				if (context != null) {
					Bundle bundles[] = context.getBundles();
					out.println();
					out.println("Id    State Modified      Location");

					for (int i = 0; i < bundles.length; i++) {
						String loc = bundles[i].getLocation();
						loc = loc.replaceAll("\\w+:", "");
						File f = new File(loc);
						out.print(fill(Long.toString(bundles[i].getBundleId()), 6));
						out.print(fill(toState(bundles[i].getState()), 6));
						if (f.exists())
							out.print(fill(toDate(f.lastModified()), 14));
						else
							out.print(fill("<>", 14));

						if (errors.containsKey(bundles[i])) {
							out.print(fill(loc, 50));
							out.print(errors.get(bundles[i]).toString());
						} else
							out.print(bundles[i].getLocation());

						out.println();
					}
				}
			}
		} catch (Throwable t) {
			error("Sorry, can't print framework: %s", t);
		}
	}

	private void row(PrintStream out, Object... parms) {
		boolean fill = true;
		for (Object p : parms) {
			if (fill)
				out.print(fill(p + "", 40));
			else
				out.print(p);
			fill = false;
		}
		out.println();
	}

	String toDate(long t) {
		Calendar c = Calendar.getInstance();
		c.setTimeInMillis(t);
		return fill(c.get(Calendar.YEAR), 4) + fill(c.get(Calendar.MONTH), 2) + fill(c.get(Calendar.DAY_OF_MONTH), 2)
				+ fill(c.get(Calendar.HOUR_OF_DAY), 2) + fill(c.get(Calendar.MINUTE), 2);
	}

	private String fill(int n, int width) {
		return fill(Integer.toString(n), width, '0', -1);
	}

	private String fill(String s, int width) {
		return fill(s, width, ' ', -1);
	}

	private String fill(String s, int width, char filler, int dir) {
		StringBuilder sb = new StringBuilder();
		if (s.length() > width) {
			int half = (width - 1) / 2;
			return s.substring(0, half) + ".." + s.substring(s.length() - half);
		}
		width -= s.length();
		int before = (dir == 0) ? width / 2 : (dir < 0) ? 0 : width;
		int after = width - before;

		while (before-- > 0)
			sb.append(filler);

		sb.append(s);

		while (after-- > 0)
			sb.append(filler);

		return sb.toString();
	}

	private String toState(int state) {
		switch (state) {
			case Bundle.INSTALLED :
				return "INSTL";
			case Bundle.RESOLVED :
				return "RSLVD";
			case Bundle.STARTING :
				return "STRTD";
			case Bundle.STOPPING :
				return "STPPD";
			case Bundle.ACTIVE :
				return "ACTIV";
			case Bundle.UNINSTALLED :
				return "UNNST";
		}
		return "? " + state;
	}

	private void list(PrintStream out, String del, List< ? > l) {
		for (Object o : l) {
			String s = o.toString();
			out.print(del);
			out.println(s);
			del = fill(" ", 40);
		}
	}

	public int translateToError(BundleException e) {
		switch (e.getType()) {
			case BundleException.ACTIVATOR_ERROR :
				return LauncherConstants.ACTIVATOR_ERROR;

			case BundleException.DUPLICATE_BUNDLE_ERROR :
				return LauncherConstants.DUPLICATE_BUNDLE;

			case BundleException.RESOLVE_ERROR :
				return LauncherConstants.RESOLVE_ERROR;

			case BundleException.INVALID_OPERATION :
			case BundleException.MANIFEST_ERROR :
			case BundleException.NATIVECODE_ERROR :
			case BundleException.STATECHANGE_ERROR :
			case BundleException.UNSUPPORTED_OPERATION :
			case BundleException.UNSPECIFIED :
			default :
				return ERROR;
		}
	}

	public String translateToMessage(BundleException e) {
		switch (e.getType()) {
			case BundleException.ACTIVATOR_ERROR :
				Throwable t = e.getCause();
				StackTraceElement[] stackTrace = t.getStackTrace();
				if (stackTrace == null || stackTrace.length == 0)
					return "activator error " + t;
				StackTraceElement top = stackTrace[0];
				return "activator error " + t.getMessage() + " from: " + top.getClassName() + ":" + top.getMethodName()
						+ "#" + top.getLineNumber();

			case BundleException.DUPLICATE_BUNDLE_ERROR :
			case BundleException.RESOLVE_ERROR :
			case BundleException.INVALID_OPERATION :
			case BundleException.MANIFEST_ERROR :
			case BundleException.NATIVECODE_ERROR :
			case BundleException.STATECHANGE_ERROR :
			case BundleException.UNSUPPORTED_OPERATION :
			case BundleException.UNSPECIFIED :
			default :
				return e.getMessage();
		}
	}

	static PermissionCollection all = new AllPermissionCollection();

	class AllPolicy extends Policy {

		@Override
		public PermissionCollection getPermissions(CodeSource codesource) {
			if (codesource == null)
				trace("Granting AllPermission to a bundle without codesource!");
			else
				trace("Granting AllPermission to %s", codesource.getLocation());
			return all;
		}

		@Override
		public void refresh() {
			trace("Policy refresh");
		}
	}

	static class AllPermissionCollection extends PermissionCollection {
		private static final long			serialVersionUID	= 1L;
		private static Vector<Permission>	list				= new Vector<Permission>();

		static {
			list.add(new AllPermission());
		}

		{
			setReadOnly();
		}

		@Override
		public void add(Permission permission) {}

		@Override
		public Enumeration<Permission> elements() {
			return list.elements();
		}

		@Override
		public boolean implies(Permission permission) {
			return true;
		}
	}

	/**
	 * Monitor the services. If a service is registered with the {@code
	 * main.thread} property then check if it is a {@code Runnable} (priority
	 * for backward compatibility) or a {@code Callable<Integer>}. If so, we set
	 * it as the main thread runner and call it once the initialization is all
	 * done.
	 */

	@SuppressWarnings("unchecked")
	public synchronized void serviceChanged(ServiceEvent event) {
		if (event.getType() == ServiceEvent.REGISTERED) {
			final Object service = systemBundle.getBundleContext().getService(event.getServiceReference());
			String[] objectclasses = (String[]) event.getServiceReference().getProperty(Constants.OBJECTCLASS);

			// This looks a bit more complicated than necessary but for backward
			// compatibility reasons we require the Callable or Runnable to be
			// registered as such. Under that condition, we prefer the Callable.

			for (String objectclass : objectclasses) {
				if (Callable.class.getName().equals(objectclass)) {
					Method m;
					try {
						m = service.getClass().getMethod("call");
						if (m.getReturnType() != Integer.class)
							throw new IllegalArgumentException(
									"Found a main thread service which is Callable<" + m.getReturnType().getName()
											+ "> which should be Callable<Integer> " + event.getServiceReference());
						mainThread = (Callable<Integer>) service;
					} catch (NoSuchMethodException e) {
						assert false;
					}
				}
			}
			if (mainThread == null) {
				mainThread = new Callable<Integer>() {
					public Integer call() throws Exception {
						((Runnable) service).run();
						return 0;
					}
				};
			}
			notifyAll();
		}
	}

	public void trace(String msg, Object... objects) {
		if (parms.trace) {
			message("# ", msg, objects);
		}
	}

	private void message(String prefix, String string, Object[] objects) {
		Throwable e = null;

		StringBuilder sb = new StringBuilder();
		int n = 0;
		sb.append(prefix);
		for (int i = 0; i < string.length(); i++) {
			char c = string.charAt(i);
			if (c == '%') {
				c = string.charAt(++i);
				switch (c) {
					case 's' :
						if (n < objects.length) {
							Object o = objects[n++];
							if (o instanceof Throwable) {
								Throwable t = e = (Throwable) o;
								if (t instanceof BundleException) {
									sb.append(translateToMessage((BundleException) t));
								} else {
									while (t instanceof InvocationTargetException) {
										Throwable cause = t.getCause();
										if (cause == null) {
											break;
										}
										t = cause;
									}
									sb.append(t.getMessage());
								}
							} else {
								sb.append(o);
							}
						} else
							sb.append("<no more arguments>");
						break;

					default :
						sb.append(c);
				}
			} else {
				sb.append(c);
			}
		}
		String message = sb.toString();
		out.println(message);
		if (e != null)
			e.printStackTrace(out);
		out.flush();

		DatagramSocket socket = commsSocket.get();

		if (socket != null) {
			int severity;
			if (message.startsWith("! ")) {
				severity = 0; // NotificationType.ERROR.ordinal();
			} else if (message.startsWith("# ") && parms.trace) {
				severity = 2; // NotificationType.INFO.ordinal();
			} else {
				return;
			}

			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(outputStream);
			try {
				dos.writeInt(severity);
				dos.writeUTF(message.substring(2));

				byte[] byteArray = outputStream.toByteArray();
				socket.send(new DatagramPacket(byteArray, byteArray.length));
			} catch (IOException ioe) {
				out.println("! Unable to send notification to " + socket.getRemoteSocketAddress());
				if (parms.trace)
					ioe.printStackTrace(out);
				out.flush();
			}
		}

	}

	public void error(String msg, Object... objects) {
		message("! ", msg, objects);
	}

	/**
	 * Find a bundle by its location.
	 * 
	 * @param path the location to find
	 */
	private Bundle getBundleByLocation(String path) {
		BundleContext context = systemBundle.getBundleContext();
		for (Bundle b : context.getBundles()) {
			if (b.getLocation().equals(path))
				return b;
		}
		return null;
	}
}
