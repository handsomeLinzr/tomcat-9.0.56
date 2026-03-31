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
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.ConnectException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;

import org.apache.catalina.Container;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Server;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.security.SecurityConfig;
import org.apache.juli.ClassLoaderLogManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.digester.Digester;
import org.apache.tomcat.util.digester.Rule;
import org.apache.tomcat.util.digester.RuleSet;
import org.apache.tomcat.util.file.ConfigFileLoader;
import org.apache.tomcat.util.file.ConfigurationSource;
import org.apache.tomcat.util.log.SystemLogHandler;
import org.apache.tomcat.util.res.StringManager;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;


/**
 * Startup/Shutdown shell program for Catalina.  The following command line
 * options are recognized:
 * <ul>
 * <li><b>-config {pathname}</b> - Set the pathname of the configuration file
 *     to be processed.  If a relative path is specified, it will be
 *     interpreted as relative to the directory pathname specified by the
 *     "catalina.base" system property.   [conf/server.xml]</li>
 * <li><b>-help</b>      - Display usage information.</li>
 * <li><b>-nonaming</b>  - Disable naming support.</li>
 * <li><b>configtest</b> - Try to test the config</li>
 * <li><b>start</b>      - Start an instance of Catalina.</li>
 * <li><b>stop</b>       - Stop the currently running instance of Catalina.</li>
 * </ul>
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public class Catalina {


    /**
     * The string manager for this package.
     */
    protected static final StringManager sm =
        StringManager.getManager(Constants.Package);

    public static final String SERVER_XML = "conf/server.xml";

    // ----------------------------------------------------- Instance Variables

    // 启动 Bootstrap 后，start 命令中会被反射设置为 true
    /**
     * Use await.
     */
    protected boolean await = false;

    // 配置文件，默认是 conf/server.xml
    /**
     * Pathname to the server configuration file.
     */
    protected String configFile = SERVER_XML;

    // 设置父类加载器是 Catalina 类对应类的类加载器 sharedLoader
    // XXX Should be moved to embedded
    /**
     * The shared extensions class loader for this server.
     */
    protected ClassLoader parentClassLoader =
        Catalina.class.getClassLoader();


    /**
     * The server component we are starting or stopping.
     */
    protected Server server = null;


    /**
     * Use shutdown hook flag.
     */
    protected boolean useShutdownHook = true;


    /**
     * Shutdown hook.
     */
    protected Thread shutdownHook = null;


    /**
     * Is naming enabled ?
     */
    protected boolean useNaming = true;


    // 启动标识，避免重复加载启动
    /**
     * Prevent duplicate loads.
     */
    protected boolean loaded = false;


    /**
     * Generate Tomcat embedded code from configuration files.
     */
    protected boolean generateCode = false;


    /**
     * Location of generated sources.
     */
    protected File generatedCodeLocation = null;


    /**
     * Value of the argument.
     */
    protected String generatedCodeLocationParameter = null;


    /**
     * Top package name for generated source.
     */
    protected String generatedCodePackage = "catalinaembedded";


    /**
     * Use generated code as a replacement for configuration files.
     */
    protected boolean useGeneratedCode = false;


    // ----------------------------------------------------------- Constructors

    // 构造函数
    public Catalina() {
        // 设置 security 配属属性相关
        setSecurityProtection();
        // 默认无操作
        ExceptionUtils.preload();
    }


    // ------------------------------------------------------------- Properties

    public void setConfigFile(String file) {
        configFile = file;
    }


    public String getConfigFile() {
        return configFile;
    }


    public void setUseShutdownHook(boolean useShutdownHook) {
        this.useShutdownHook = useShutdownHook;
    }


    public boolean getUseShutdownHook() {
        return useShutdownHook;
    }


    public boolean getGenerateCode() {
        return this.generateCode;
    }


    public void setGenerateCode(boolean generateCode) {
        this.generateCode = generateCode;
    }


    public boolean getUseGeneratedCode() {
        return this.useGeneratedCode;
    }


    public void setUseGeneratedCode(boolean useGeneratedCode) {
        this.useGeneratedCode = useGeneratedCode;
    }


    public File getGeneratedCodeLocation() {
        return this.generatedCodeLocation;
    }


    public void setGeneratedCodeLocation(File generatedCodeLocation) {
        this.generatedCodeLocation = generatedCodeLocation;
    }


    public String getGeneratedCodePackage() {
        return this.generatedCodePackage;
    }


    public void setGeneratedCodePackage(String generatedCodePackage) {
        this.generatedCodePackage = generatedCodePackage;
    }


    // 设置共享的外部类加载器
    // 在 Bootstrap.init 方法中，通过反射调用，设置了类加载器 sharedLoader
    /**
     * Set the shared extensions class loader.
     *
     * @param parentClassLoader The shared extensions class loader.
     */
    public void setParentClassLoader(ClassLoader parentClassLoader) {
        this.parentClassLoader = parentClassLoader;
    }

    public ClassLoader getParentClassLoader() {
        if (parentClassLoader != null) {
            return parentClassLoader;
        }
        return ClassLoader.getSystemClassLoader();
    }

    public void setServer(Server server) {
        this.server = server;
    }


    public Server getServer() {
        return server;
    }


    /**
     * @return <code>true</code> if naming is enabled.
     */
    public boolean isUseNaming() {
        return this.useNaming;
    }


    /**
     * Enables or disables naming support.
     *
     * @param useNaming The new use naming value
     */
    public void setUseNaming(boolean useNaming) {
        this.useNaming = useNaming;
    }

    // 被 Bootstrap 反射设置为 true
    public void setAwait(boolean b) {
        await = b;
    }

    public boolean isAwait() {
        return await;
    }

    // ------------------------------------------------------ Protected Methods


    // 处理指定的命令行参数
    /**
     * Process the specified command line arguments.
     *
     * @param args Command line arguments to process
     * @return <code>true</code> if we should continue processing
     */
    protected boolean arguments(String args[]) {

        boolean isConfig = false;
        boolean isGenerateCode = false;

        if (args.length < 1) {
            usage();
            return false;
        }

        for (String arg : args) {
            if (isConfig) {
                configFile = arg;
                isConfig = false;
            } else if (arg.equals("-config")) {
                isConfig = true;
            } else if (arg.equals("-generateCode")) {
                setGenerateCode(true);
                isGenerateCode = true;
            } else if (arg.equals("-useGeneratedCode")) {
                setUseGeneratedCode(true);
                isGenerateCode = false;
            } else if (arg.equals("-nonaming")) {
                setUseNaming(false);
                isGenerateCode = false;
            } else if (arg.equals("-help")) {
                usage();
                return false;
            } else if (arg.equals("start")) {
                // 启动，设置 isGenerateCode 生成代码标识为 false
                isGenerateCode = false;
                // NOOP
            } else if (arg.equals("configtest")) {
                isGenerateCode = false;
                // NOOP
            } else if (arg.equals("stop")) {
                isGenerateCode = false;
                // NOOP
            } else if (isGenerateCode) {
                generatedCodeLocationParameter = arg;
                isGenerateCode = false;
            } else {
                usage();
                return false;
            }
        }

        return true;
    }


    // 定义一个文件 fd，指向配置文件 server.xml
    /**
     * Return a File object representing our configuration file.
     * @return the main configuration file
     */
    protected File configFile() {

        File file = new File(configFile);
        if (!file.isAbsolute()) {
            file = new File(Bootstrap.getCatalinaBase(), configFile);
        }
        return file;

    }


    // 创建并启动 Digester
    // 添加了一系列的规则
    /**
     * Create and configure the Digester we will be using for startup.
     * @return the main digester to parse server.xml
     */
    protected Digester createStartDigester() {
        // Initialize the digester
        // 初始化 Digester
        Digester digester = new Digester();
        // 设置校验 false
        digester.setValidating(false);
        // 设置需要规则验证
        digester.setRulesValidation(true);

        // 用来存解析到的结果
        Map<Class<?>, List<String>> fakeAttributes = new HashMap<>();

        // 添加 className
        // Ignore className on all elements
        List<String> objectAttrs = new ArrayList<>();
        // 添加 className 标签
        objectAttrs.add("className");
        fakeAttributes.put(Object.class, objectAttrs);

        // 添加 source
        // Ignore attribute added by Eclipse for its internal tracking
        List<String> contextAttrs = new ArrayList<>();
        contextAttrs.add("source");
        fakeAttributes.put(StandardContext.class, contextAttrs);

        // 添加 portOffset
        // Ignore Connector attribute used internally but set on Server
        List<String> connectorAttrs = new ArrayList<>();
        connectorAttrs.add("portOffset");
        fakeAttributes.put(Connector.class, connectorAttrs);

        // 设置属性 fakeAttributes
        digester.setFakeAttributes(fakeAttributes);
        digester.setUseContextClassLoader(true);

        // 配置需要使用的类
        // Configure the actions we will be using
        // 添加新的规则 ObjectCreateRule
        digester.addObjectCreate("Server",
                                 "org.apache.catalina.core.StandardServer",
                                 "className");
        // 添加新的规则 SetPropertiesRule
        digester.addSetProperties("Server");
        // 添加新的规则 SetNextRule
        digester.addSetNext("Server",
                            "setServer",
                            "org.apache.catalina.Server");

        digester.addObjectCreate("Server/GlobalNamingResources",
                                 "org.apache.catalina.deploy.NamingResourcesImpl");
        digester.addSetProperties("Server/GlobalNamingResources");
        digester.addSetNext("Server/GlobalNamingResources",
                            "setGlobalNamingResources",
                            "org.apache.catalina.deploy.NamingResourcesImpl");

        digester.addRule("Server/Listener",
                new ListenerCreateRule(null, "className"));
        digester.addSetProperties("Server/Listener");
        digester.addSetNext("Server/Listener",
                            "addLifecycleListener",
                            "org.apache.catalina.LifecycleListener");

        digester.addObjectCreate("Server/Service",
                                 "org.apache.catalina.core.StandardService",
                                 "className");
        digester.addSetProperties("Server/Service");
        digester.addSetNext("Server/Service",
                            "addService",
                            "org.apache.catalina.Service");

        digester.addObjectCreate("Server/Service/Listener",
                                 null, // MUST be specified in the element
                                 "className");
        digester.addSetProperties("Server/Service/Listener");
        digester.addSetNext("Server/Service/Listener",
                            "addLifecycleListener",
                            "org.apache.catalina.LifecycleListener");

        //Executor
        digester.addObjectCreate("Server/Service/Executor",
                         "org.apache.catalina.core.StandardThreadExecutor",
                         "className");
        digester.addSetProperties("Server/Service/Executor");

        digester.addSetNext("Server/Service/Executor",
                            "addExecutor",
                            "org.apache.catalina.Executor");

        digester.addRule("Server/Service/Connector",
                         new ConnectorCreateRule());
        digester.addSetProperties("Server/Service/Connector",
                new String[]{"executor", "sslImplementationName", "protocol"});
        digester.addSetNext("Server/Service/Connector",
                            "addConnector",
                            "org.apache.catalina.connector.Connector");

        digester.addRule("Server/Service/Connector", new AddPortOffsetRule());

        digester.addObjectCreate("Server/Service/Connector/SSLHostConfig",
                                 "org.apache.tomcat.util.net.SSLHostConfig");
        digester.addSetProperties("Server/Service/Connector/SSLHostConfig");
        digester.addSetNext("Server/Service/Connector/SSLHostConfig",
                "addSslHostConfig",
                "org.apache.tomcat.util.net.SSLHostConfig");

        digester.addRule("Server/Service/Connector/SSLHostConfig/Certificate",
                         new CertificateCreateRule());
        digester.addSetProperties("Server/Service/Connector/SSLHostConfig/Certificate", new String[]{"type"});
        digester.addSetNext("Server/Service/Connector/SSLHostConfig/Certificate",
                            "addCertificate",
                            "org.apache.tomcat.util.net.SSLHostConfigCertificate");

        digester.addObjectCreate("Server/Service/Connector/SSLHostConfig/OpenSSLConf",
                                 "org.apache.tomcat.util.net.openssl.OpenSSLConf");
        digester.addSetProperties("Server/Service/Connector/SSLHostConfig/OpenSSLConf");
        digester.addSetNext("Server/Service/Connector/SSLHostConfig/OpenSSLConf",
                            "setOpenSslConf",
                            "org.apache.tomcat.util.net.openssl.OpenSSLConf");

        digester.addObjectCreate("Server/Service/Connector/SSLHostConfig/OpenSSLConf/OpenSSLConfCmd",
                                 "org.apache.tomcat.util.net.openssl.OpenSSLConfCmd");
        digester.addSetProperties("Server/Service/Connector/SSLHostConfig/OpenSSLConf/OpenSSLConfCmd");
        digester.addSetNext("Server/Service/Connector/SSLHostConfig/OpenSSLConf/OpenSSLConfCmd",
                            "addCmd",
                            "org.apache.tomcat.util.net.openssl.OpenSSLConfCmd");

        digester.addObjectCreate("Server/Service/Connector/Listener",
                                 null, // MUST be specified in the element
                                 "className");
        digester.addSetProperties("Server/Service/Connector/Listener");
        digester.addSetNext("Server/Service/Connector/Listener",
                            "addLifecycleListener",
                            "org.apache.catalina.LifecycleListener");

        digester.addObjectCreate("Server/Service/Connector/UpgradeProtocol",
                                  null, // MUST be specified in the element
                                  "className");
        digester.addSetProperties("Server/Service/Connector/UpgradeProtocol");
        digester.addSetNext("Server/Service/Connector/UpgradeProtocol",
                            "addUpgradeProtocol",
                            "org.apache.coyote.UpgradeProtocol");

        // Add RuleSets for nested elements
        digester.addRuleSet(new NamingRuleSet("Server/GlobalNamingResources/"));
        digester.addRuleSet(new EngineRuleSet("Server/Service/"));
        digester.addRuleSet(new HostRuleSet("Server/Service/Engine/"));
        digester.addRuleSet(new ContextRuleSet("Server/Service/Engine/Host/"));
        addClusterRuleSet(digester, "Server/Service/Engine/Host/Cluster/");
        digester.addRuleSet(new NamingRuleSet("Server/Service/Engine/Host/Context/"));

        // When the 'engine' is found, set the parentClassLoader.
        digester.addRule("Server/Service/Engine",
                         new SetParentClassLoaderRule(parentClassLoader));
        addClusterRuleSet(digester, "Server/Service/Engine/Cluster/");

        return digester;

    }

    /**
     * Cluster support is optional. The JARs may have been removed.
     */
    private void addClusterRuleSet(Digester digester, String prefix) {
        Class<?> clazz = null;
        Constructor<?> constructor = null;
        try {
            clazz = Class.forName("org.apache.catalina.ha.ClusterRuleSet");
            constructor = clazz.getConstructor(String.class);
            RuleSet ruleSet = (RuleSet) constructor.newInstance(prefix);
            digester.addRuleSet(ruleSet);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("catalina.noCluster",
                        e.getClass().getName() + ": " +  e.getMessage()), e);
            } else if (log.isInfoEnabled()) {
                log.info(sm.getString("catalina.noCluster",
                        e.getClass().getName() + ": " +  e.getMessage()));
            }
        }
    }

    /**
     * Create and configure the Digester we will be using for shutdown.
     * @return the digester to process the stop operation
     */
    protected Digester createStopDigester() {

        // Initialize the digester
        Digester digester = new Digester();
        digester.setUseContextClassLoader(true);

        // Configure the rules we need for shutting down
        digester.addObjectCreate("Server",
                                 "org.apache.catalina.core.StandardServer",
                                 "className");
        digester.addSetProperties("Server");
        digester.addSetNext("Server",
                            "setServer",
                            "org.apache.catalina.Server");

        return digester;

    }


    /**
     * 解析 server.xml
     * @param start 默认传 true
     */
    protected void parseServerXml(boolean start) {
        // 设置配置源
        // Set configuration source
        // getConfigFile ->> conf/server.xml
        // 设置配置文件源
        ConfigFileLoader.setSource(new CatalinaBaseConfigurationSource(Bootstrap.getCatalinaBaseFile(), getConfigFile()));
        // 指向 conf/server.xml 文件
        File file = configFile();

        if (useGeneratedCode && !Digester.isGeneratedCodeLoaderSet()) {
            // 源码编译
            // Load loader
            String loaderClassName = generatedCodePackage + ".DigesterGeneratedCodeLoader";
            try {
                // 源码编码器
                Digester.GeneratedCodeLoader loader = (Digester.GeneratedCodeLoader)
                        Catalina.class.getClassLoader().loadClass(loaderClassName).getDeclaredConstructor().newInstance();
                Digester.setGeneratedCodeLoader(loader);
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.info(sm.getString("catalina.noLoader", loaderClassName), e);
                } else {
                    log.info(sm.getString("catalina.noLoader", loaderClassName));
                }
                // No loader so don't use generated code
                useGeneratedCode = false;
            }
        }

        // Init source location
        File serverXmlLocation = null;
        String xmlClassName = null;
        if (generateCode || useGeneratedCode) {
            xmlClassName = start ? generatedCodePackage + ".ServerXml" : generatedCodePackage + ".ServerXmlStop";
        }
        if (generateCode) {
            if (generatedCodeLocationParameter != null) {
                generatedCodeLocation = new File(generatedCodeLocationParameter);
                if (!generatedCodeLocation.isAbsolute()) {
                    generatedCodeLocation = new File(Bootstrap.getCatalinaHomeFile(), generatedCodeLocationParameter);
                }
            } else {
                generatedCodeLocation = new File(Bootstrap.getCatalinaHomeFile(), "work");
            }
            serverXmlLocation = new File(generatedCodeLocation, generatedCodePackage);
            if (!serverXmlLocation.isDirectory() && !serverXmlLocation.mkdirs()) {
                log.warn(sm.getString("catalina.generatedCodeLocationError", generatedCodeLocation.getAbsolutePath()));
                // Disable code generation
                generateCode = false;
            }
        }

        ServerXml serverXml = null;
        if (useGeneratedCode) {
            serverXml = (ServerXml) Digester.loadGeneratedClass(xmlClassName);
        }

        if (serverXml != null) {
            serverXml.load(this);
        } else {
            // 获取到 server.xml 文件 resource
            try (ConfigurationSource.Resource resource = ConfigFileLoader.getSource().getServerXml()) {
                // Create and execute our Digester
                // 创建并启动外部 Digester，这里先默认 start = true
                // 在 Digester 中添加了很多的规则，比如 server 那些
                Digester digester = start ? createStartDigester() : createStopDigester();

                // 获取 server.xml 文件的输入流
                InputStream inputStream = resource.getInputStream();
                InputSource inputSource = new InputSource(resource.getURI().toURL().toString());
                inputSource.setByteStream(inputStream);
                // 将当前的 catalina 推到 digester 栈顶
                digester.push(this);
                if (generateCode) {
                    digester.startGeneratingCode();
                    generateClassHeader(digester, start);
                }
                // 解析 server.xml 输入流
                digester.parse(inputSource);
                if (generateCode) {
                    generateClassFooter(digester);
                    try (FileWriter writer = new FileWriter(new File(serverXmlLocation,
                            start ? "ServerXml.java" : "ServerXmlStop.java"))) {
                        writer.write(digester.getGeneratedCode().toString());
                    }
                    digester.endGeneratingCode();
                    Digester.addGeneratedClass(xmlClassName);
                }
            } catch (Exception e) {
                log.warn(sm.getString("catalina.configFail", file.getAbsolutePath()), e);
                if (file.exists() && !file.canRead()) {
                    log.warn(sm.getString("catalina.incorrectPermissions"));
                }
            }
        }
    }

    public void stopServer() {
        stopServer(null);
    }

    public void stopServer(String[] arguments) {

        if (arguments != null) {
            arguments(arguments);
        }

        Server s = getServer();
        if (s == null) {
            parseServerXml(false);
            if (getServer() == null) {
                log.error(sm.getString("catalina.stopError"));
                System.exit(1);
            }
        } else {
            // Server object already present. Must be running as a service
            try {
                s.stop();
                s.destroy();
            } catch (LifecycleException e) {
                log.error(sm.getString("catalina.stopError"), e);
            }
            return;
        }

        // Stop the existing server
        s = getServer();
        if (s.getPortWithOffset() > 0) {
            try (Socket socket = new Socket(s.getAddress(), s.getPortWithOffset());
                    OutputStream stream = socket.getOutputStream()) {
                String shutdown = s.getShutdown();
                for (int i = 0; i < shutdown.length(); i++) {
                    stream.write(shutdown.charAt(i));
                }
                stream.flush();
            } catch (ConnectException ce) {
                log.error(sm.getString("catalina.stopServer.connectException", s.getAddress(),
                        String.valueOf(s.getPortWithOffset()), String.valueOf(s.getPort()),
                        String.valueOf(s.getPortOffset())));
                log.error(sm.getString("catalina.stopError"), ce);
                System.exit(1);
            } catch (IOException e) {
                log.error(sm.getString("catalina.stopError"), e);
                System.exit(1);
            }
        } else {
            log.error(sm.getString("catalina.stopServer"));
            System.exit(1);
        }
    }


    // 启动一个 server 实例
    /**
     * Start a new server instance.
     */
    public void load() {

        if (loaded) {
            // 判断假如已经加载了，则直接返回
            return;
        }
        // 设置加载标识为 true，下次则不会重复加载
        loaded = true;

        // 当前时间
        long t1 = System.nanoTime();

        // 初始化文件目录，默认空实现
        // tomcat10将要移除
        initDirs();

        // 设置 naming 相关的系统配置变量
        // Before digester - it may be needed
        initNaming();

        // 解析 server.xml 文件，server.xml 就是为了启动 server 实例的
        // Parse main server.xml
        // 走完这里，其实已经创建了一个 Server 对象，并且已经调用 setServer 设置进去了
        parseServerXml(true);
        // 获取 server 实例，StandardServer
        Server s = getServer();
        if (s == null) {
            return;
        }

        // 设置对应 server 的关联 catalina 是当前对象
        getServer().setCatalina(this);
        // 设置对应的 catalina 路径
        getServer().setCatalinaHome(Bootstrap.getCatalinaHomeFile());
        getServer().setCatalinaBase(Bootstrap.getCatalinaBaseFile());

        // 初始化流，其实就是输入输出流，可以忽略
        // Stream redirection
        initStreams();

        // Start the new server
        try {
            // 初始化 server
            // 调用到了生命周期
            getServer().init();
        } catch (LifecycleException e) {
            if (Boolean.getBoolean("org.apache.catalina.startup.EXIT_ON_INIT_FAILURE")) {
                throw new java.lang.Error(e);
            } else {
                log.error(sm.getString("catalina.initError"), e);
            }
        }

        if(log.isInfoEnabled()) {
            log.info(sm.getString("catalina.init", Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1))));
        }
    }


    // 加载，被 Bootstrap 反射调用，start 的情况，这时候参数传递的是 start
    /*
     * Load using arguments
     */
    public void load(String args[]) {

        try {
            // 处理命令行参数，这里用 start
            if (arguments(args)) {
                // 加载
                load();
            }
        } catch (Exception e) {
            e.printStackTrace(System.out);
        }
    }


    // 启动一个新的 server 实例
    /**
     * Start a new server instance.
     */
    public void start() {

        if (getServer() == null) {
            // 如果当前还没有 server，则先加载
            // 默认前边已经加载了
            load();
        }

        if (getServer() == null) {
            log.fatal(sm.getString("catalina.noServer"));
            return;
        }

        long t1 = System.nanoTime();

        // Start the new server
        try {
            // 调用 server 的 start，启动 standardServer 服务
            // 继续走到 LifecycleBase 声明周期类
            getServer().start();
        } catch (LifecycleException e) {
            log.fatal(sm.getString("catalina.serverStartFail"), e);
            try {
                getServer().destroy();
            } catch (LifecycleException e1) {
                log.debug("destroy() failed for failed Server ", e1);
            }
            return;
        }

        if (log.isInfoEnabled()) {
            log.info(sm.getString("catalina.startup", Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1))));
        }

        if (generateCode) {
            // Generate loader which will load all generated classes
            generateLoader();
        }

        // Register shutdown hook
        if (useShutdownHook) {
            if (shutdownHook == null) {
                shutdownHook = new CatalinaShutdownHook();
            }
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            // If JULI is being used, disable JULI's shutdown hook since
            // shutdown hooks run in parallel and log messages may be lost
            // if JULI's hook completes before the CatalinaShutdownHook()
            LogManager logManager = LogManager.getLogManager();
            if (logManager instanceof ClassLoaderLogManager) {
                ((ClassLoaderLogManager) logManager).setUseShutdownHook(
                        false);
            }
        }

        if (await) {
            await();
            stop();
        }
    }


    /**
     * Stop an existing server instance.
     */
    public void stop() {

        try {
            // Remove the ShutdownHook first so that server.stop()
            // doesn't get invoked twice
            if (useShutdownHook) {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);

                // If JULI is being used, re-enable JULI's shutdown to ensure
                // log messages are not lost
                LogManager logManager = LogManager.getLogManager();
                if (logManager instanceof ClassLoaderLogManager) {
                    ((ClassLoaderLogManager) logManager).setUseShutdownHook(
                            true);
                }
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // This will fail on JDK 1.2. Ignoring, as Tomcat can run
            // fine without the shutdown hook.
        }

        // Shut down the server
        try {
            Server s = getServer();
            LifecycleState state = s.getState();
            if (LifecycleState.STOPPING_PREP.compareTo(state) <= 0
                    && LifecycleState.DESTROYED.compareTo(state) >= 0) {
                // Nothing to do. stop() was already called
            } else {
                s.stop();
                s.destroy();
            }
        } catch (LifecycleException e) {
            log.error(sm.getString("catalina.stopError"), e);
        }

    }


    /**
     * Await and shutdown.
     */
    public void await() {

        getServer().await();

    }


    /**
     * Print usage information for this application.
     */
    protected void usage() {

        System.out.println(sm.getString("catalina.usage"));

    }


    /**
     * @deprecated unused. Will be removed in Tomcat 10 onwards.
     */
    @Deprecated
    protected void initDirs() {
    }


    protected void initStreams() {
        // Replace System.out and System.err with a custom PrintStream
        System.setOut(new SystemLogHandler(System.out));
        System.setErr(new SystemLogHandler(System.err));
    }


    /**
     * 初始化关于 naming 的配置变量，设置到系统变量中
     */
    protected void initNaming() {
        // 设置额外属性
        // Setting additional variables
        if (!useNaming) {
            log.info(sm.getString("catalina.noNaming"));
            System.setProperty("catalina.useNaming", "false");
        } else {
            // 设置系统属性 catalina.useNaming = true
            System.setProperty("catalina.useNaming", "true");
            // 要设置的值
            String value = "org.apache.naming";
            String oldValue =
                // java.naming.factory.url.pkgs
                System.getProperty(javax.naming.Context.URL_PKG_PREFIXES);
            if (oldValue != null) {
                // 如果已经有该系统变量，则用冒号连接，添加该值
                value = value + ":" + oldValue;
            }
            // 设置系统属性 java.naming.factory.url.pkgs 为 org.apache.naming
            System.setProperty(javax.naming.Context.URL_PKG_PREFIXES, value);
            if( log.isDebugEnabled() ) {
                log.debug("Setting naming prefix=" + value);
            }
            // 获取系统属性 java.naming.factory.initial
            value = System.getProperty
                (javax.naming.Context.INITIAL_CONTEXT_FACTORY);
            if (value == null) {
                // 如果没有设置，则进行设置为 org.apache.naming.java.javaURLContextFactory
                System.setProperty
                    (javax.naming.Context.INITIAL_CONTEXT_FACTORY,
                     "org.apache.naming.java.javaURLContextFactory");
            } else {
                log.debug("INITIAL_CONTEXT_FACTORY already set " + value );
            }
        }
    }


    // 设置 SecurityConfig 相关的配置
    /**
     * Set the security package access/protection.
     */
    protected void setSecurityProtection(){
        // 构建安全协议相关的配置
        SecurityConfig securityConfig = SecurityConfig.newInstance();
        // 设置默认 package.definition 设置
        securityConfig.setPackageDefinition();
        // 设置默认 package.access 设置
        securityConfig.setPackageAccess();
    }


    protected void generateLoader() {
        String loaderClassName = "DigesterGeneratedCodeLoader";
        StringBuilder code = new StringBuilder();
        code.append("package ").append(generatedCodePackage).append(";").append(System.lineSeparator());
        code.append("public class ").append(loaderClassName);
        code.append(" implements org.apache.tomcat.util.digester.Digester.GeneratedCodeLoader {").append(System.lineSeparator());
        code.append("public Object loadGeneratedCode(String className) {").append(System.lineSeparator());
        code.append("switch (className) {").append(System.lineSeparator());
        for (String generatedClassName : Digester.getGeneratedClasses()) {
            code.append("case \"").append(generatedClassName).append("\" : return new ").append(generatedClassName);
            code.append("();").append(System.lineSeparator());
        }
        code.append("default: return null; }").append(System.lineSeparator());
        code.append("}}").append(System.lineSeparator());
        File loaderLocation = new File(generatedCodeLocation, generatedCodePackage);
        try (FileWriter writer = new FileWriter(new File(loaderLocation, loaderClassName + ".java"))) {
            writer.write(code.toString());
        } catch (IOException e) {
            // Should not happen
            log.debug("Error writing code loader", e);
        }
    }


    protected void generateClassHeader(Digester digester, boolean start) {
        StringBuilder code = digester.getGeneratedCode();
        code.append("package ").append(generatedCodePackage).append(";").append(System.lineSeparator());
        code.append("public class ServerXml");
        if (!start) {
            code.append("Stop");
        }
        code.append(" implements ");
        code.append(ServerXml.class.getName().replace('$', '.')).append(" {").append(System.lineSeparator());
        code.append("public void load(").append(Catalina.class.getName());
        code.append(' ').append(digester.toVariableName(this)).append(") {").append(System.lineSeparator());
    }


    protected void generateClassFooter(Digester digester) {
        StringBuilder code = digester.getGeneratedCode();
        code.append('}').append(System.lineSeparator());
        code.append('}').append(System.lineSeparator());
    }


    public interface ServerXml {
        public void load(Catalina catalina);
    }


    // --------------------------------------- CatalinaShutdownHook Inner Class

    // XXX Should be moved to embedded !
    /**
     * Shutdown hook which will perform a clean shutdown of Catalina if needed.
     */
    protected class CatalinaShutdownHook extends Thread {

        @Override
        public void run() {
            try {
                if (getServer() != null) {
                    Catalina.this.stop();
                }
            } catch (Throwable ex) {
                ExceptionUtils.handleThrowable(ex);
                log.error(sm.getString("catalina.shutdownHookFail"), ex);
            } finally {
                // If JULI is used, shut JULI down *after* the server shuts down
                // so log messages aren't lost
                LogManager logManager = LogManager.getLogManager();
                if (logManager instanceof ClassLoaderLogManager) {
                    ((ClassLoaderLogManager) logManager).shutdown();
                }
            }
        }
    }


    private static final Log log = LogFactory.getLog(Catalina.class);


    /**
     * Rule that sets the parent class loader for the top object on the stack,
     * which must be a <code>Container</code>.
     */

    final class SetParentClassLoaderRule extends Rule {

        public SetParentClassLoaderRule(ClassLoader parentClassLoader) {

            this.parentClassLoader = parentClassLoader;

        }

        ClassLoader parentClassLoader = null;

        @Override
        public void begin(String namespace, String name, Attributes attributes)
            throws Exception {

            if (digester.getLogger().isDebugEnabled()) {
                digester.getLogger().debug("Setting parent class loader");
            }

            Container top = (Container) digester.peek();
            top.setParentClassLoader(parentClassLoader);

            StringBuilder code = digester.getGeneratedCode();
            if (code != null) {
                code.append(digester.toVariableName(top)).append(".setParentClassLoader(");
                code.append(digester.toVariableName(Catalina.this)).append(".getParentClassLoader());");
                code.append(System.lineSeparator());
            }
        }

    }

}
