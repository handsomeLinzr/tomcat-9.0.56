/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.startup;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.catalina.security.SecurityClassLoad;
import org.apache.catalina.startup.ClassLoaderFactory.Repository;
import org.apache.catalina.startup.ClassLoaderFactory.RepositoryType;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

// tomcat 核心类 Bootstrap，加载 Catalina，构建一个类加载器，用于加载Catalina多的内部类，并开始容器运行
// 这种迂回方法的目的是将Catalina内部类（以及它们所依赖的任何其他类，如XML解析器）排除在系统类路径之外，因此对应用程序级类不可见。
/**
 * Bootstrap loader for Catalina.  This application constructs a class loader
 * for use in loading the Catalina internal classes (by accumulating all of the
 * JAR files found in the "server" directory under "catalina.home"), and
 * starts the regular execution of the container.  The purpose of this
 * roundabout approach is to keep the Catalina internal classes (and any
 * other classes they depend on, such as an XML parser) out of the system
 * class path and therefore not visible to application level classes.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public final class Bootstrap {

    // 日志
    private static final Log log = LogFactory.getLog(Bootstrap.class);

    /**
     * Daemon object used by main.
     */
    private static final Object daemonLock = new Object();
    // 当前 main 方法启动后，创建的 Bootstrap
    private static volatile Bootstrap daemon = null;

    // 两个文件
    private static final File catalinaBaseFile;
    private static final File catalinaHomeFile;

    // 分隔正则表达式
    private static final Pattern PATH_PATTERN = Pattern.compile("(\"[^\"]*\")|(([^,])*)");

    // 静态代码块，优先处理
    // 其实就是解析 catalinaHomeFile 和 catalinaBaseFile，通过当前用户目录
    static {
        // 用户目录
        // Will always be non-null
        String userDir = System.getProperty("user.dir");

        // catalina.home 容器目录
        // Home first
        String home = System.getProperty(Constants.CATALINA_HOME_PROP);
        File homeFile = null;

        if (home != null) {
            // 如果指定了 catalina.home，则赋值给 homeFile
            File f = new File(home);
            try {
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile();
            }
        }

        // 如果没有 homeFile，则从当前用户目录下，找到 bootstrap.jar 文件，设置 homeFile
        if (homeFile == null) {
            // First fall-back. See if current directory is a bin directory
            // in a normal Tomcat install
            File bootstrapJar = new File(userDir, "bootstrap.jar");

            if (bootstrapJar.exists()) {
                File f = new File(userDir, "..");
                try {
                    homeFile = f.getCanonicalFile();
                } catch (IOException ioe) {
                    homeFile = f.getAbsoluteFile();
                }
            }
        }

        // 否则指向 userDir
        if (homeFile == null) {
            // Second fall-back. Use current directory
            File f = new File(userDir);
            try {
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile();
            }
        }

        // 赋值给 catalinaHomeFile
        catalinaHomeFile = homeFile;
        // 设置到当前系统环境
        System.setProperty(
                Constants.CATALINA_HOME_PROP, catalinaHomeFile.getPath());

        // 获取 base 路径
        // Then base
        String base = System.getProperty(Constants.CATALINA_BASE_PROP);
        if (base == null) {
            // 没设置，则默认和 catalinaHomeFile 一样
            catalinaBaseFile = catalinaHomeFile;
        } else {
            // 有设置，则设置路径
            File baseFile = new File(base);
            try {
                baseFile = baseFile.getCanonicalFile();
            } catch (IOException ioe) {
                baseFile = baseFile.getAbsoluteFile();
            }
            catalinaBaseFile = baseFile;
        }
        // 设置系统变量
        System.setProperty(
                Constants.CATALINA_BASE_PROP, catalinaBaseFile.getPath());
    }

    // -------------------------------------------------------------- Variables


    // catalina 实例
    /**
     * Daemon reference.
     */
    private Object catalinaDaemon = null;

    // common 类加载器
    //  "${catalina.base}/lib",
    //  "${catalina.base}/lib/*.jar",
    //  "${catalina.home}/lib",
    //  "${catalina.home}/lib/*.jar"
    ClassLoader commonLoader = null;
    ClassLoader catalinaLoader = null;
    ClassLoader sharedLoader = null;


    // -------------------------------------------------------- Private Methods


    private void initClassLoaders() {
        try {
            // 创建类加载器 common，顶级，没有父类加载
            // 打破双亲委派
            commonLoader = createClassLoader("common", null);
            if (commonLoader == null) {
                // 没有配置文件，默认当前类的类加载器
                // no config file, default to this loader - we might be in a 'single' env.
                commonLoader = this.getClass().getClassLoader();
            }
            // 同样步骤创建 server 的类加载器，默认不设置则是 commonLoader
            catalinaLoader = createClassLoader("server", commonLoader);
            // 创建 shared 类加载器， 默认不设置则是 commonLoader
            sharedLoader = createClassLoader("shared", commonLoader);
        } catch (Throwable t) {
            handleThrowable(t);
            log.error("Class loader creation threw exception", t);
            System.exit(1);
        }
    }


    private ClassLoader createClassLoader(String name, ClassLoader parent)
        throws Exception {

        // 默认读到了
        // common.loader=
        //  "${catalina.base}/lib",
        //  "${catalina.base}/lib/*.jar",
        //  "${catalina.home}/lib",
        //  "${catalina.home}/lib/*.jar"
        String value = CatalinaProperties.getProperty(name + ".loader");
        if ((value == null) || (value.equals(""))) {
            return parent;
        }

        // 解析成实际value
        value = replace(value);

        List<Repository> repositories = new ArrayList<>();

        // 将 value 切分
        String[] repositoryPaths = getPaths(value);

        // 遍历切分到的路径和对应的 jar
        for (String repository : repositoryPaths) {
            // Check for a JAR URL repository
            try {
                @SuppressWarnings("unused")
                URL url = new URL(repository);
                // 添加对应的路径到 repositories 中
                repositories.add(new Repository(repository, RepositoryType.URL));
                continue;
            } catch (MalformedURLException e) {
                // Ignore
            }

            // 区分类型
            // Local repository
            if (repository.endsWith("*.jar")) {
                repository = repository.substring
                    (0, repository.length() - "*.jar".length());
                repositories.add(new Repository(repository, RepositoryType.GLOB));
            } else if (repository.endsWith(".jar")) {
                repositories.add(new Repository(repository, RepositoryType.JAR));
            } else {
                repositories.add(new Repository(repository, RepositoryType.DIR));
            }
        }

        // 创建需要加载的解析路径下的文件的类加载器
        return ClassLoaderFactory.createClassLoader(repositories, parent);
    }


    // 根据系统变量，解析对应的表达式
    /**
     * System property replacement in the given string.
     *
     * @param str The original string
     * @return the modified string
     */
    protected String replace(String str) {
        // Implementation is copied from ClassLoaderLogManager.replace(),
        // but added special processing for catalina.home and catalina.base.
        String result = str;
        int pos_start = str.indexOf("${");
        if (pos_start >= 0) {
            StringBuilder builder = new StringBuilder();
            int pos_end = -1;
            while (pos_start >= 0) {
                builder.append(str, pos_end + 1, pos_start);
                pos_end = str.indexOf('}', pos_start + 2);
                if (pos_end < 0) {
                    pos_end = pos_start - 1;
                    break;
                }
                String propName = str.substring(pos_start + 2, pos_end);
                String replacement;
                if (propName.length() == 0) {
                    replacement = null;
                } else if (Constants.CATALINA_HOME_PROP.equals(propName)) {
                    replacement = getCatalinaHome();
                } else if (Constants.CATALINA_BASE_PROP.equals(propName)) {
                    replacement = getCatalinaBase();
                } else {
                    replacement = System.getProperty(propName);
                }
                if (replacement != null) {
                    builder.append(replacement);
                } else {
                    builder.append(str, pos_start, pos_end + 1);
                }
                pos_start = str.indexOf("${", pos_end + 1);
            }
            builder.append(str, pos_end + 1, str.length());
            result = builder.toString();
        }
        return result;
    }


    // 1.初始化 3 个类加载器
    // 2.设置类加载器 catalinaClassLoader（从 server.loader 加载）到当前线程类加载器中
    // 3.创建Catalina 对象，并反射设置对应的 parentClassLoader 为当前的 sharedClassLoader（从 shared.loader 加载）
    // 4.赋值反射创建的 catalina 对象给到 catalinaDaemon 属性
    /**
     * Initialize daemon.
     * @throws Exception Fatal initialization error
     */
    public void init() throws Exception {

        // 初始化类加载器，加载 3 个
        //      commonLoader
        //      catalinaLoader
        //      sharedLoader
        initClassLoaders();

        // 设置类加载器 catalinaLoader 到当前线程中
        Thread.currentThread().setContextClassLoader(catalinaLoader);

        // 设置安全管理的类加载器，也是这个
        SecurityClassLoad.securityClassLoad(catalinaLoader);

        // debug
        // Load our startup class and call its process() method
        if (log.isDebugEnabled()) {
            log.debug("Loading startup class");
        }
        // 反射获取 Catalina 并创建实例
        Class<?> startupClass = catalinaLoader.loadClass("org.apache.catalina.startup.Catalina");
        Object startupInstance = startupClass.getConstructor().newInstance();

        // Set the shared extensions class loader
        if (log.isDebugEnabled()) {
            log.debug("Setting startup class properties");
        }
        // 方法名称
        String methodName = "setParentClassLoader";
        // 参数
        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Class.forName("java.lang.ClassLoader");
        Object paramValues[] = new Object[1];
        // 共享类加载器
        paramValues[0] = sharedLoader;
        // 反射调用方法，设置 Catalina 的类加载器
        Method method =
            startupInstance.getClass().getMethod(methodName, paramTypes);
        method.invoke(startupInstance, paramValues);

        catalinaDaemon = startupInstance;
    }


    // 反射调用 catalina.load
    /**
     * Load daemon.
     */
    private void load(String[] arguments) throws Exception {

        // 反射调用 load 方法
        // Call the load() method
        String methodName = "load";
        Object param[];
        Class<?> paramTypes[];
        if (arguments==null || arguments.length==0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        // 反射调用 catalina.load 方法
        Method method =
            catalinaDaemon.getClass().getMethod(methodName, paramTypes);
        if (log.isDebugEnabled()) {
            log.debug("Calling startup class " + method);
        }
        method.invoke(catalinaDaemon, param);
    }


    /**
     * getServer() for configtest
     */
    private Object getServer() throws Exception {

        String methodName = "getServer";
        Method method = catalinaDaemon.getClass().getMethod(methodName);
        return method.invoke(catalinaDaemon);
    }


    // ----------------------------------------------------------- Main Program


    /**
     * Load the Catalina daemon.
     * @param arguments Initialization arguments
     * @throws Exception Fatal initialization error
     */
    public void init(String[] arguments) throws Exception {

        init();
        load(arguments);
    }


    /**
     * Start the Catalina daemon.
     * @throws Exception Fatal start error
     */
    public void start() throws Exception {
        if (catalinaDaemon == null) {
            // 如果 catalinaDaemon 是空的，则需要调用 init 进行初始化
            // 这里因为前边在 Bootstrap 中的 load 已经加载过了，所以不用走到这里
            init();
        }

        // 反射调用 catalina.start 方法启动
        Method method = catalinaDaemon.getClass().getMethod("start", (Class [])null);
        method.invoke(catalinaDaemon, (Object [])null);
    }


    /**
     * Stop the Catalina Daemon.
     * @throws Exception Fatal stop error
     */
    public void stop() throws Exception {
        Method method = catalinaDaemon.getClass().getMethod("stop", (Class []) null);
        method.invoke(catalinaDaemon, (Object []) null);
    }


    /**
     * Stop the standalone server.
     * @throws Exception Fatal stop error
     */
    public void stopServer() throws Exception {

        Method method =
            catalinaDaemon.getClass().getMethod("stopServer", (Class []) null);
        method.invoke(catalinaDaemon, (Object []) null);
    }


   /**
     * Stop the standalone server.
     * @param arguments Command line arguments
     * @throws Exception Fatal stop error
     */
    public void stopServer(String[] arguments) throws Exception {

        Object param[];
        Class<?> paramTypes[];
        if (arguments == null || arguments.length == 0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method =
            catalinaDaemon.getClass().getMethod("stopServer", paramTypes);
        method.invoke(catalinaDaemon, param);
    }


    // 设置等待标识，表示守护线程需要阻塞
    /**
     * Set flag.
     * @param await <code>true</code> if the daemon should block
     * @throws Exception Reflection error
     */
    public void setAwait(boolean await)
        throws Exception {

        // 构建参数
        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Boolean.TYPE;
        Object paramValues[] = new Object[1];
        paramValues[0] = Boolean.valueOf(await);
        // 反射调用 catalinaDaemon.setAwait 方法，设置为传进来的值，默认是 true
        Method method =
            catalinaDaemon.getClass().getMethod("setAwait", paramTypes);
        method.invoke(catalinaDaemon, paramValues);
    }

    public boolean getAwait() throws Exception {
        Class<?> paramTypes[] = new Class[0];
        Object paramValues[] = new Object[0];
        Method method =
            catalinaDaemon.getClass().getMethod("getAwait", paramTypes);
        Boolean b=(Boolean)method.invoke(catalinaDaemon, paramValues);
        return b.booleanValue();
    }


    /**
     * Destroy the Catalina Daemon.
     */
    public void destroy() {

        // FIXME

    }


    /**
     * Main method and entry point when starting Tomcat via the provided
     * scripts.
     *
     * @param args Command line arguments to be processed
     */
    public static void main(String args[]) {

        // 先加锁
        synchronized (daemonLock) {
            // 判断 daemon 为空才需要创建
            // 避免重复创建
            if (daemon == null) {
                // Don't set daemon until init() has completed
                // 创建 Bootstrap
                // 根据路径获取和设置 catalinaHomeFile 和 catalinaBaseFile
                Bootstrap bootstrap = new Bootstrap();
                try {
                    // 初始化
                    // 初始化类加载器，创建 catalina 对象，设置类加载器
                    bootstrap.init();
                } catch (Throwable t) {
                    handleThrowable(t);
                    t.printStackTrace();
                    return;
                }
                // 赋值给 daemon
                daemon = bootstrap;
            } else {
                // When running as a service the call to stop will be on a new
                // thread so make sure the correct class loader is used to
                // prevent a range of class not found exceptions.
                Thread.currentThread().setContextClassLoader(daemon.catalinaLoader);
            }
        }

        try {
            // 如果有启动参数，则根据启动参数，没有启动参数则默认是启动 start
            String command = "start";
            if (args.length > 0) {
                command = args[args.length - 1];
            }

            if (command.equals("startd")) {
                args[args.length - 1] = "start";
                // 反射调用 catalina.start 方法
                daemon.load(args);
                daemon.start();
            } else if (command.equals("stopd")) {
                args[args.length - 1] = "stop";
                daemon.stop();
            } else if (command.equals("start")) {
                // 启动 tomcat 服务
                // 设置标识
                daemon.setAwait(true);
                // 加载守护线程，会反射调用到 catalina.load 方法
                daemon.load(args);
                // 启动，反射调用 catalina.start 启动
                daemon.start();
                if (null == daemon.getServer()) {
                    System.exit(1);
                }
            } else if (command.equals("stop")) {
                daemon.stopServer(args);
            } else if (command.equals("configtest")) {
                daemon.load(args);
                if (null == daemon.getServer()) {
                    System.exit(1);
                }
                System.exit(0);
            } else {
                log.warn("Bootstrap: command \"" + command + "\" does not exist.");
            }
        } catch (Throwable t) {
            // Unwrap the Exception for clearer error reporting
            if (t instanceof InvocationTargetException &&
                    t.getCause() != null) {
                t = t.getCause();
            }
            handleThrowable(t);
            t.printStackTrace();
            System.exit(1);
        }
    }


    /**
     * Obtain the name of configured home (binary) directory. Note that home and
     * base may be the same (and are by default).
     * @return the catalina home
     */
    public static String getCatalinaHome() {
        return catalinaHomeFile.getPath();
    }


    /**
     * Obtain the name of the configured base (instance) directory. Note that
     * home and base may be the same (and are by default). If this is not set
     * the value returned by {@link #getCatalinaHome()} will be used.
     * @return the catalina base
     */
    public static String getCatalinaBase() {
        return catalinaBaseFile.getPath();
    }


    /**
     * Obtain the configured home (binary) directory. Note that home and
     * base may be the same (and are by default).
     * @return the catalina home as a file
     */
    public static File getCatalinaHomeFile() {
        return catalinaHomeFile;
    }


    /**
     * Obtain the configured base (instance) directory. Note that
     * home and base may be the same (and are by default). If this is not set
     * the value returned by {@link #getCatalinaHomeFile()} will be used.
     * @return the catalina base as a file
     */
    public static File getCatalinaBaseFile() {
        return catalinaBaseFile;
    }


    // Copied from ExceptionUtils since that class is not visible during start
    static void handleThrowable(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof StackOverflowError) {
            // Swallow silently - it should be recoverable
            return;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
        // All other instances of Throwable will be silently swallowed
    }

    // Copied from ExceptionUtils so that there is no dependency on utils
    static Throwable unwrapInvocationTargetException(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    // Protected for unit testing
    protected static String[] getPaths(String value) {

        List<String> result = new ArrayList<>();
        // 按正则表达是切分
        Matcher matcher = PATH_PATTERN.matcher(value);

        // 遍历切分的结果
        while (matcher.find()) {
            // 获取路径
            String path = value.substring(matcher.start(), matcher.end());

            // 去掉空格
            path = path.trim();
            if (path.length() == 0) {
                continue;
            }

            char first = path.charAt(0);
            char last = path.charAt(path.length() - 1);

            if (first == '"' && last == '"' && path.length() > 1) {
                path = path.substring(1, path.length() - 1);
                path = path.trim();
                if (path.length() == 0) {
                    continue;
                }
            } else if (path.contains("\"")) {
                // Unbalanced quotes
                // Too early to use standard i18n support. The class path hasn't
                // been configured.
                throw new IllegalArgumentException(
                        "The double quote [\"] character can only be used to quote paths. It must " +
                        "not appear in a path. This loader path is not valid: [" + value + "]");
            } else {
                // Not quoted - NO-OP
            }

            // 将路径添加到 result 中
            result.add(path);
        }

        // 返回收集到的结果
        return result.toArray(new String[0]);
    }
}
