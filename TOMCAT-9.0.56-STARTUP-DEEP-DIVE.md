# Tomcat 9.0.56 启动源码精读

这是一份只讲启动链路的专题文档，建议配合总览文档一起看：

- [总览文档](./TOMCAT-9.0.56-STUDY-GUIDE.md)

这份文档的目标不是“列出所有类”，而是带你把启动链路真正跟通一次。


## 1. 先背下启动总链路

Tomcat 默认启动主线可以先记成这一条：

`bin/catalina.sh`
-> `org.apache.catalina.startup.Bootstrap.main()`
-> `Bootstrap.init()`
-> `Bootstrap.load(args)`
-> `Catalina.load()`
-> `Catalina.parseServerXml(true)`
-> 构建 `StandardServer/StandardService/Connector/Engine/Host/...`
-> `StandardServer.init()`
-> `Bootstrap.start()`
-> `Catalina.start()`
-> `StandardServer.start()`
-> `StandardService.start()`
-> `StandardEngine.start()`
-> `StandardHost.start()`
-> `StandardContext.start()`
-> `StandardWrapper.start()`
-> `await()`

第一遍读源码时，不要扩散，先把这一条线跟到底。


## 2. 从脚本进入 Java 世界

入口脚本在：

- `bin/catalina.sh`
- `bin/startup.sh`

你研究源码时不用一行行抠 shell，重点只需要确认：

1. 最终启动的是 `org.apache.catalina.startup.Bootstrap`
2. 参数一般会传成 `start`
3. 停止时通常传成 `stop`

也就是说，Java 世界里的真正入口就是：

- `java/org/apache/catalina/startup/Bootstrap.java`


## 3. `Bootstrap.main()` 做了什么

重点位置：

- `Bootstrap.main()` 在 506 行附近

建议你逐句盯下面几件事：

1. 全局单例 `daemon`
2. 首次启动时创建 `Bootstrap` 对象
3. 先 `bootstrap.init()`
4. 再根据命令决定走 `load/start/stop/configtest`

### 3.1 `start` 命令的执行路径

当命令是 `start` 时，核心动作是：

1. `daemon.setAwait(true)`
2. `daemon.load(args)`
3. `daemon.start()`

所以“启动 Tomcat”在 Java 里其实分成两段：

1. `load`
   解析配置，创建对象，做初始化
2. `start`
   进入真正的生命周期启动

这个分层非常重要，后面会一直看到。


## 4. `Bootstrap.init()` 是引导器真正的核心

重点位置：

- `Bootstrap.init()` 在 297 行附近

这里要抓住 3 件事：

1. 初始化类加载器
2. 设置线程上下文类加载器
3. 反射创建 `Catalina`

### 4.1 `catalina.home` 和 `catalina.base`

类初始化阶段，`Bootstrap` 先在静态代码块中解析：

- `catalina.home`
- `catalina.base`

默认情况下：

- `catalina.home` 指向安装目录
- `catalina.base` 没单独配置时，和 `home` 相同

这两个路径后面会反复出现：

- 读 `conf/`
- 找 `webapps/`
- 找 `lib/`
- 创建 `work/`

### 4.2 类加载器初始化

关键方法：

- `initClassLoaders()`
- `createClassLoader()`

关键配置来源：

- `conf/catalina.properties`

默认逻辑是：

1. 创建 `commonLoader`
2. 再创建 `catalinaLoader`
3. 再创建 `sharedLoader`

默认配置下：

- `server.loader` 为空，`catalinaLoader` 最终常退化为 `commonLoader`
- `shared.loader` 为空，`sharedLoader` 也常退化为 `commonLoader`

### 4.3 反射创建 `Catalina`

`Bootstrap.init()` 里会：

1. 用 `catalinaLoader` 加载 `org.apache.catalina.startup.Catalina`
2. 反射创建实例
3. 反射调用 `setParentClassLoader(sharedLoader)`
4. 保存到 `catalinaDaemon`

所以 `Bootstrap` 不直接依赖 `Catalina` 实现细节，它只负责把舞台搭起来。


## 5. `Bootstrap.load()` 到 `Catalina.load()`

重点位置：

- `Bootstrap.load()` 在 345 行附近
- `Catalina.load()` 在 746 行附近

这是“把配置变成对象图”的阶段。

### 5.1 `Bootstrap.load()` 做得很少

它只是反射调用：

- `Catalina.load(args)`

真正复杂的是 `Catalina.load()`。

### 5.2 `Catalina.load()` 的主流程

读这个方法时，建议你只抓下面步骤：

1. 防重复加载
2. `initDirs()`
3. `initNaming()`
4. `parseServerXml(true)`
5. `server.setCatalina(this)`
6. `server.setCatalinaHome(...)`
7. `server.setCatalinaBase(...)`
8. `initStreams()`
9. `server.init()`

这意味着：

- `load()` 阶段结束后，Tomcat 的主要对象已经被创建好了
- 只是还没进入真正的 `start()`


## 6. `parseServerXml(true)` 是启动对象图构建入口

重点位置：

- `Catalina.parseServerXml(true)` 在 589 行附近
- `Catalina.createStartDigester()` 在 380 行附近

### 6.1 先记住一件事

Tomcat 不是“读 XML 后手写 new 一堆对象”，而是：

用 `Digester` 把 XML 规则映射成对象创建和装配动作。

### 6.2 `createStartDigester()` 里最重要的规则

你可以重点看这些：

- `Server` -> `StandardServer`
- `Server/GlobalNamingResources` -> `NamingResourcesImpl`
- `Server/Listener` -> `LifecycleListener`
- `Server/Service` -> `StandardService`
- `Server/Service/Executor` -> `StandardThreadExecutor`
- `Server/Service/Connector` -> `Connector`
- `Server/Service/Engine` -> `EngineRuleSet`
- `Server/Service/Engine/Host` -> `HostRuleSet`
- `Server/Service/Engine/Host/Context` -> `ContextRuleSet`

这就是为什么 `server.xml` 的层次结构和运行时对象树高度一致。

### 6.3 `digester.push(this)` 的意义

`parseServerXml()` 里会先把 `Catalina` 自己压栈。

这样当解析到：

- `Server`

时，规则就能调用：

- `Catalina.setServer(Server)`

所以 `Catalina` 成了整个对象图的顶层持有者。


## 7. `server.xml` 解析后对象树长什么样

默认 `conf/server.xml` 对应的核心对象关系可以画成：

`Catalina`
-> `StandardServer`
-> `StandardService("Catalina")`
-> `Connector(8080)`
-> `StandardEngine("Catalina")`
-> `StandardHost("localhost")`
-> 若干 `Context`
-> 若干 `Wrapper`

这里要特别注意两点：

1. `Server` 和 `Service` 不是 `Container`
2. 真正的容器树从 `Engine` 才开始


## 8. `server.init()` 到底初始化了什么

当 `Catalina.load()` 调用：

- `getServer().init()`

就正式进入统一生命周期框架。

建议你把下面这几个 `initInternal()` 连着看：

1. `StandardServer.initInternal()`
2. `StandardService.initInternal()`
3. `StandardEngine.initInternal()`
4. `StandardHost.initInternal()`
5. `StandardContext.initInternal()`
6. `Connector.initInternal()`

### 8.1 `LifecycleBase.init()` 的套路

所有这些组件大体都遵循：

1. 检查状态必须是 `NEW`
2. 切到 `INITIALIZING`
3. 调子类 `initInternal()`
4. 切到 `INITIALIZED`

所以你看任何组件初始化时，都要问自己：

- 这个组件在 `initInternal()` 里做了哪些“只初始化不启动”的动作？

### 8.2 `StandardServer.initInternal()`

重点理解：

1. `Server` 本身是生命周期根节点
2. 它负责把 `Service` 全部带着一起初始化
3. 它也会初始化全局 naming 资源和公用线程池

### 8.3 `StandardService.initInternal()`

重点动作：

1. `engine.init()`
2. 初始化各个 `Executor`
3. 初始化 `MapperListener`
4. 初始化各个 `Connector`

注意这个顺序很关键：

映射监听器和容器要先准备好，连接器才能 later 正常启动。

### 8.4 `Connector.initInternal()`

这是连接器初始化的关键点。

它会做：

1. 创建 `CoyoteAdapter`
2. `protocolHandler.setAdapter(adapter)`
3. 设置 utility executor
4. `protocolHandler.init()`

所以从这里开始：

- Catalina 世界的 `Connector`
- Coyote 世界的 `ProtocolHandler`

就真正绑定起来了。


## 9. `Bootstrap.start()` 到 `Catalina.start()`

重点位置：

- `Bootstrap.start()` 在 401 行附近
- `Catalina.start()` 在 827 行附近

### 9.1 `Bootstrap.start()`

这一步仍然只是反射：

- `Catalina.start()`

### 9.2 `Catalina.start()` 的主流程

这段代码建议你顺序看：

1. 若 `server == null`，先补一次 `load()`
2. `getServer().start()`
3. 注册 shutdown hook
4. 如果 `await = true`，就进入 `await()`
5. `await()` 返回后再 `stop()`

这里把两个概念分清：

- `start()` 是生命周期启动
- `await()` 是主线程阻塞等待关闭命令

Tomcat 能持续运行，不是因为 `start()` 不返回，而是因为它后面进入了 `await()`。


## 10. `StandardServer.start()` 是启动总控

重点类：

- `java/org/apache/catalina/core/StandardServer.java`

重点方法：

- `startInternal()`

它的主流程很干净：

1. 触发 `CONFIGURE_START_EVENT`
2. 切到 `STARTING`
3. 启动全局 naming resources
4. 逐个启动 `Service`
5. 启动周期性生命周期事件调度

所以可以把 `StandardServer` 看成：

整个 Tomcat 生命周期向下扩散的第一个总控节点。


## 11. `StandardService.start()` 的顺序为什么这样排

重点方法：

- `StandardService.startInternal()`

执行顺序：

1. 启动 `Engine`
2. 启动各个 `Executor`
3. 启动 `MapperListener`
4. 启动各个 `Connector`

为什么必须这样：

1. 容器树先就绪
2. 线程池和映射表先可用
3. 最后再开始接收外部请求

如果先启动 `Connector`，请求进来时容器树和映射信息可能还不完整。


## 12. 容器树启动是如何递归下去的

你从这里开始要连续跟：

- `StandardEngine.startInternal()`
- `ContainerBase.startInternal()`
- `StandardHost.startInternal()`
- `StandardContext.startInternal()`
- `StandardWrapper.startInternal()`

### 12.1 `ContainerBase.startInternal()` 是关键公共逻辑

它做的事情基本是：

1. 启动 cluster
2. 启动 realm
3. 并发启动所有子容器
4. 启动本容器 pipeline
5. 切到 `STARTING`

所以：

- `Engine` 启动时会把 `Host` 拉起来
- `Host` 启动时会把 `Context` 拉起来
- `Context` 启动时会把 `Wrapper` 拉起来

这就是容器树生命周期递归扩散的核心。


## 13. `StandardContext.startInternal()` 是 Web 应用启动重头戏

这一段启动最复杂，建议拆段看。

### 13.1 先准备运行环境

典型动作：

1. naming resources start
2. 准备 work 目录
3. 初始化默认 `StandardRoot`
4. `resourcesStart()`
5. 创建 `WebappLoader`
6. 初始化字符集映射和 cookieProcessor

### 13.2 再启动类加载和运行时基础设施

典型动作：

1. 启动 `Loader`
2. 拿到 `WebappClassLoaderBase`
3. 设置泄漏清理相关参数
4. 启动 Realm
5. 触发 `CONFIGURE_START_EVENT`

这里的 `CONFIGURE_START_EVENT` 很重要，因为：

- `ContextConfig` 就是靠监听这个事件来装配 Web 应用定义的

### 13.3 再装配应用定义

这一段实际上是 `ContextConfig.configureStart()` 干的。

主要包括：

1. 解析 `web.xml`
2. 合并 `web-fragment.xml`
3. 扫描 SCI
4. 扫描注解
5. 配置 filter/listener/servlet/security/JSP

### 13.4 最后拉起应用组件

典型动作：

1. 创建并启动 `Manager`
2. 执行 `ServletContainerInitializer.onStartup()`
3. `listenerStart()`
4. `filterStart()`
5. `loadOnStartup()`

这一段结束后，一个 Web 应用才算真的 ready。


## 14. `HostConfig` 在启动链路里的位置

重点类：

- `java/org/apache/catalina/startup/HostConfig.java`

`HostConfig` 是 `Host` 的 `LifecycleListener`。

它在启动阶段最关键的作用是：

1. 扫描 `appBase`
2. 部署目录、WAR、外部 Context XML
3. 给每个应用创建 `Context`

所以如果你发现：

- `StandardHost` 下面的 `Context` 是怎么来的？

通常答案就是：

- 很多是 `HostConfig` 在启动过程中部署出来的

研究这个问题时建议连着看：

- `HostConfig.lifecycleEvent()`
- `HostConfig.start()`
- `HostConfig.deployApps()`


## 15. `ContextConfig` 在启动链路里的位置

重点类：

- `java/org/apache/catalina/startup/ContextConfig.java`

`ContextConfig` 也是监听器，但它监听的是 `Context` 生命周期。

最关键触发点：

- `Lifecycle.CONFIGURE_START_EVENT`

主入口：

- `ContextConfig.configureStart()`

建议你把它理解成：

- `StandardContext` 负责“启动应用框架”
- `ContextConfig` 负责“把应用的声明式配置装进去”


## 16. `MapperListener` 为什么启动时必须先拉起来

重点类：

- `java/org/apache/catalina/mapper/MapperListener.java`

主入口：

- `startInternal()`

它启动时做的几件事：

1. 找默认 host
2. 给 `Engine/Host/Context/Wrapper` 注册监听器
3. 把已启动的 host/context/wrapper 注册进 `Mapper`

所以启动期里有一条非常重要但容易漏掉的线：

`StandardService.startInternal()`
-> `mapperListener.start()`
-> `registerHost()`
-> `registerContext()`
-> `registerWrapper()`
-> `Mapper` 建立运行时映射表

如果这张表没建好，后面请求进来就无法快速定位到 `Host/Context/Wrapper`。


## 17. `await()` 为什么是启动链路最后一环

重点位置：

- `Catalina.await()`
- `StandardServer.await()`

### 17.1 `Catalina.await()`

非常简单：

- 直接委托给 `server.await()`

### 17.2 `StandardServer.await()`

这个方法会：

1. 打开关闭监听 socket
2. 阻塞等待连接
3. 读取关闭命令字符串
4. 与 `shutdown` 配置比较
5. 命中后退出等待

默认配置来自：

- `conf/server.xml`
- `<Server port="8005" shutdown="SHUTDOWN">`

所以 Tomcat 默认“停机口令关闭”是这么实现的。


## 18. 停机链路怎么读

建议你不要只看启动，也顺便看停机。

主线：

`Bootstrap.main(stop)`
-> `Bootstrap.stopServer(args)`
-> `Catalina.stopServer(args)`
-> 连到 `Server` 的 shutdown 端口
-> `StandardServer.await()` 读到关闭命令
-> `Catalina.stop()`
-> `Server.stop()`
-> `Service.stop()`
-> `Engine/Host/Context/Wrapper.stop()`

这条线能帮你反过来理解生命周期停止顺序。


## 19. 启动源码建议的断点顺序

下面这组断点足够你把主链打一遍：

1. `Bootstrap.main()`
2. `Bootstrap.init()`
3. `Bootstrap.createClassLoader()`
4. `Bootstrap.load()`
5. `Catalina.load()`
6. `Catalina.createStartDigester()`
7. `Catalina.parseServerXml()`
8. `StandardServer.initInternal()`
9. `StandardService.initInternal()`
10. `Connector.initInternal()`
11. `Catalina.start()`
12. `StandardServer.startInternal()`
13. `StandardService.startInternal()`
14. `MapperListener.startInternal()`
15. `ContainerBase.startInternal()`
16. `StandardContext.startInternal()`
17. `ContextConfig.configureStart()`
18. `HostConfig.deployApps()`
19. `StandardServer.await()`

如果你只想先打一条最短路径，保留这 8 个就够：

1. `Bootstrap.main()`
2. `Bootstrap.init()`
3. `Catalina.load()`
4. `Catalina.parseServerXml()`
5. `StandardServer.initInternal()`
6. `Catalina.start()`
7. `StandardService.startInternal()`
8. `StandardContext.startInternal()`


## 20. 跟读时建议回答这几个问题

1. 为什么 `Bootstrap` 要反射加载 `Catalina`？
2. 为什么 `load()` 和 `start()` 要分开？
3. `server.xml` 是在哪一步变成对象树的？
4. `Context` 是什么时候被部署出来的？
5. `Mapper` 是什么时候建立起来的？
6. 为什么 `Connector` 必须最后启动？
7. `await()` 和 `start()` 各自负责什么？
8. `ContextConfig` 和 `HostConfig` 分工有什么区别？

这 8 个问题能答顺，启动主线就算读通了。


## 21. 推荐你下一步怎么接

如果你刚跟完这份文档，最自然的下一步是切到请求链路：

- [请求处理链路精读](./TOMCAT-9.0.56-REQUEST-PIPELINE-DEEP-DIVE.md)

建议顺序：

1. 先把启动链路打通
2. 确认 Tomcat 完整启动成功
3. 再发一个真实 HTTP 请求
4. 然后沿请求链继续跟

这样两条主线会在 `Connector` 和 `StandardContext` 处真正接上。
