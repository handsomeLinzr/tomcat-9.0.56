# Tomcat 9.0.56 源码研究辅导文档

这份文档不是官方说明书，而是一份适合你跟着源码一步步分析的“研究路线图”。

目标有三个：

1. 先建立 Tomcat 整体心智模型，知道每一层在干什么。
2. 再沿着启动链路和请求链路读主干，避免一上来陷进细节。
3. 最后补齐类加载、部署、Session、JSP 这些横向专题，形成完整闭环。


## 1. 先看懂这份源码的整体结构

先不要一头扎进单个类，先把仓库分成几大块：

- `build.xml`
  Ant 构建入口。默认 target 是 `deploy`。常用 target 有 `compile`、`package`、`deploy`、`test-compile`、`test`。
- `bin/`
  启动和关闭脚本，最终会把控制权交给 `org.apache.catalina.startup.Bootstrap`。
- `conf/`
  运行期配置，最关键的是 `server.xml`、`web.xml`、`catalina.properties`、`context.xml`。
- `java/org/apache/catalina/`
  Catalina 容器本体，文件最多，核心中的核心。
- `java/org/apache/coyote/`
  协议处理层，负责 HTTP/AJP 等协议处理。
- `java/org/apache/tomcat/util/`
  通用基础设施，网络、线程池、扫描器、Digester、缓冲区等都在这里。
- `java/org/apache/jasper/`
  JSP 引擎 Jasper。
- `java/org/apache/el/`
  EL 表达式实现。
- `java/org/apache/naming/`
  JNDI/Naming 实现。
- `java/javax/`
  Servlet/JSP/EL/WebSocket 等 API。
- `test/`
  官方测试和测试用 webapp，非常适合反向理解功能点。
- `webapps/`
  默认应用，比如 `ROOT`、`examples`、`manager`、`host-manager`。

按文件量粗看，`org/apache/catalina` 是主体，`org/apache/tomcat` 是基础设施，`org/apache/coyote` 是连接器协议层。这三个包是研究主战场。


## 2. Tomcat 的核心心智模型

把 Tomcat 想成三层最容易理解：

1. 启动与配置层
   负责读取 `server.xml`，构建对象图，并启动生命周期。
2. 容器层
   负责用 `Engine -> Host -> Context -> Wrapper` 这棵树表示服务、虚拟主机、应用和 servlet。
3. 连接器与请求处理层
   负责收 socket、解析 HTTP、映射到容器对象，再调用 Filter/Servlet。

最重要的一句话：

Tomcat = `Connector` 负责“把网络请求变成容器请求”，`Container` 负责“把容器请求交给正确的应用和 servlet”。


## 3. 先读启动主线

建议第一遍只追这条链：

`bin/catalina.sh` -> `Bootstrap.main()` -> `Bootstrap.init()` -> `Catalina.load()` -> 解析 `server.xml` -> `StandardServer.start()` -> `StandardService.start()` -> `StandardEngine/Host/Context/Wrapper.start()`

### 3.1 Bootstrap 做了什么

关键类：

- `java/org/apache/catalina/startup/Bootstrap.java`

重点方法：

- `main()` 在第 506 行附近
- `init()` 在第 297 行附近
- `load()` 在第 345 行附近
- `start()` 在第 401 行附近

你读这个类时要抓住 4 件事：

1. 解析 `catalina.home` 和 `catalina.base`
2. 初始化类加载器：`commonLoader`、`catalinaLoader`、`sharedLoader`
3. 用 `catalinaLoader` 反射加载 `org.apache.catalina.startup.Catalina`
4. 再通过反射调用 `load()` / `start()` / `stopServer()`

所以 `Bootstrap` 本质上不是业务容器，它是“容器引导器”。

### 3.2 类加载器为什么要单独搞一层

看两个地方：

- `Bootstrap.createClassLoader()`：`java/org/apache/catalina/startup/Bootstrap.java`
- `conf/catalina.properties` 中的 `common.loader`、`server.loader`、`shared.loader`

默认配置下：

- `common.loader` 指向 `${catalina.base}/lib` 和 `${catalina.home}/lib`
- `server.loader` 默认空，退化为 `commonLoader`
- `shared.loader` 默认空，退化为 `commonLoader`

也就是说：

- 容器内部类的可见性是被精心设计过的
- 应用类和 Tomcat 内部类不是完全混在一起的
- 后面研究 WebApp 类加载隔离时，这里是前置知识


## 4. `Catalina` 如何把 `server.xml` 变成对象图

关键类：

- `java/org/apache/catalina/startup/Catalina.java`
- `conf/server.xml`

重点方法：

- `createStartDigester()` 在第 380 行附近
- `parseServerXml()` 在第 589 行附近
- `load()` 在第 746 行附近
- `start()` 在第 827 行附近

### 4.1 这里最核心的不是 XML，而是 Digester 规则

`Catalina.createStartDigester()` 里定义了一大堆规则，例如：

- `Server` -> `StandardServer`
- `Server/Service` -> `StandardService`
- `Server/Service/Connector` -> `Connector`
- `Server/Service/Engine` -> `StandardEngine`
- 更深层再交给 `EngineRuleSet`、`HostRuleSet`、`ContextRuleSet`

这意味着：

`server.xml` 不是简单配置文件，它会被直接映射成一棵运行时对象树。

### 4.2 用默认 `server.xml` 去对照对象层级

从 `conf/server.xml` 默认配置看：

- `<Server>` 对应一个 `StandardServer`
- `<Service name="Catalina">` 对应一个 `StandardService`
- `<Connector port="8080" protocol="HTTP/1.1">` 对应一个 `Connector`
- `<Engine name="Catalina" defaultHost="localhost">` 对应一个 `StandardEngine`
- `<Host name="localhost" appBase="webapps">` 对应一个 `StandardHost`

这里有个非常重要的认知：

`Server` 和 `Service` 不是 `Container`，真正的容器树是从 `Engine` 开始的。


## 5. 生命周期框架是 Tomcat 的骨架

关键类：

- `java/org/apache/catalina/util/LifecycleBase.java`
- `java/org/apache/catalina/Lifecycle.java`
- `java/org/apache/catalina/LifecycleState.java`

重点方法：

- `init()`
- `start()`
- `stop()`
- `destroy()`
- 子类实现的 `initInternal()` / `startInternal()` / `stopInternal()` / `destroyInternal()`

### 5.1 研究 Tomcat 时要始终带着生命周期视角

你看到绝大多数核心组件都继承了 `LifecycleBase` 或其子类。

原因很简单：

- Tomcat 里几乎所有对象都不是“new 完就能工作”
- 它们都需要按统一状态机切换
- 父组件负责驱动子组件启动和停止

`LifecycleBase.start()` 的逻辑很标准：

1. 检查状态是否合法
2. 必要时先 `init()`
3. 进入 `STARTING_PREP`
4. 调子类 `startInternal()`
5. 子类必须把状态推进到 `STARTING`
6. 最终统一切到 `STARTED`

后面你看 `Server/Service/Engine/Host/Context/Wrapper/Connector`，都建议带着这个模版看。


## 6. 容器树怎么组织

关键类：

- `StandardServer`
- `StandardService`
- `ContainerBase`
- `StandardEngine`
- `StandardHost`
- `StandardContext`
- `StandardWrapper`

### 6.1 层级关系

推荐你背下来：

- `Server`
  整个 Tomcat 实例，管理多个 `Service`
- `Service`
  把一组 `Connector` 和一个 `Engine` 绑在一起
- `Engine`
  整个 Catalina 容器入口，下面是多个 `Host`
- `Host`
  一个虚拟主机，下面是多个 `Context`
- `Context`
  一个 Web 应用，下面是多个 `Wrapper`
- `Wrapper`
  一个 servlet 定义

### 6.2 `ContainerBase` 是容器公共基类

关键类：

- `java/org/apache/catalina/core/ContainerBase.java`

重点看：

- `children`
- `pipeline`
- `addChildInternal()`
- `startInternal()`
- `stopInternal()`
- `backgroundProcess()`

这个类做了 3 件大事：

1. 维护子容器集合
2. 维护 `Pipeline/Valve` 责任链
3. 统一处理子容器的生命周期启动和停止

你可以把它看成“容器公共运行框架”。


## 7. `Pipeline` 和 `Valve` 是 Tomcat 的责任链

关键类：

- `java/org/apache/catalina/core/StandardPipeline.java`
- `java/org/apache/catalina/Valve.java`

每一层容器都有一个 `Pipeline`，里面串着多个 `Valve`。

默认最重要的是每层 basic valve：

- `StandardEngineValve`
- `StandardHostValve`
- `StandardContextValve`
- `StandardWrapperValve`

这四个 basic valve 很像“层层下钻”的默认实现。

### 7.1 这条链路要怎么理解

容器层默认调用过程大致是：

1. `Engine` 选 `Host`
2. `Host` 选 `Context`
3. `Context` 选 `Wrapper`
4. `Wrapper` 创建 FilterChain 并调用 servlet

不过要注意一个很关键的细节：

`Host/Context/Wrapper` 的选择，不是在 valve 里现算出来的，真正的映射工作更早就在 `Mapper` 里完成了。basic valve 更多是在“消费映射结果并继续下传”。


## 8. `Service` 如何把连接器和容器绑起来

关键类：

- `java/org/apache/catalina/core/StandardService.java`

重点方法：

- `initInternal()` 在第 533 行附近
- `startInternal()` 在第 422 行附近
- `stopInternal()` 在第 465 行附近

`StandardService` 的核心职责：

1. 管理一个 `Engine`
2. 管理多个 `Connector`
3. 管理 `Mapper` 和 `MapperListener`
4. 决定启动顺序

启动顺序非常重要：

1. 先启动 `Engine`
2. 再启动线程池 `Executor`
3. 再启动 `MapperListener`
4. 最后启动各个 `Connector`

这个顺序体现的是：

容器和映射表必须先就绪，连接器才能安全接收请求。


## 9. 一个 HTTP 请求是怎么走到 Servlet 的

这一段是你研究 Tomcat 时最值钱的一段。

建议主线：

`Connector` -> `ProtocolHandler` -> `Endpoint` -> `CoyoteAdapter.service()` -> `postParseRequest()` -> `Mapper.map()` -> `Engine/Host/Context/Wrapper pipeline` -> `ApplicationFilterChain` -> `Servlet.service()`

### 9.1 `Connector` 是 Catalina 和 Coyote 的桥

关键类：

- `java/org/apache/catalina/connector/Connector.java`

重点：

- 默认协议是 `HTTP/1.1`
- 实际会创建 `ProtocolHandler`
- `initInternal()` 里创建 `CoyoteAdapter`
- `startInternal()` 里启动 `protocolHandler`

默认 HTTP/1.1 会落到：

- `org.apache.coyote.http11.Http11NioProtocol`

它内部使用：

- `org.apache.tomcat.util.net.NioEndpoint`

### 9.2 `AbstractProtocol` 和 `NioEndpoint`

关键类：

- `java/org/apache/coyote/AbstractProtocol.java`
- `java/org/apache/coyote/http11/Http11NioProtocol.java`
- `java/org/apache/tomcat/util/net/NioEndpoint.java`

理解方式：

- `ProtocolHandler` 负责协议级处理
- `Endpoint` 负责底层网络 I/O
- `ConnectionHandler` 负责把 socket 事件交给 `Processor`

NIO 版本里常见线程角色：

- Acceptor 线程：接受连接
- Poller 线程：监听读写事件
- Worker 线程：真正处理请求

### 9.3 `CoyoteAdapter` 是请求进入 Catalina 的关键桥接点

关键类：

- `java/org/apache/catalina/connector/CoyoteAdapter.java`

重点方法：

- `service()` 在第 314 行附近
- `postParseRequest()` 在第 581 行附近

第一遍重点看 `postParseRequest()`，因为它做了很多“进入容器前”的工作：

1. 处理 scheme / secure / proxyName / proxyPort
2. 解码和规范化 URI
3. 解析 path parameter
4. 调用 `Mapper.map()`
5. 解析 URL/Cookie/SSL 中的 Session ID
6. 必要时重映射版本化 Context
7. 处理目录重定向
8. 拦截 TRACE 等特殊请求

### 9.4 `Mapper` 决定请求要落到哪个 Host/Context/Wrapper

关键类：

- `java/org/apache/catalina/mapper/Mapper.java`

重点方法：

- `map()` 在第 690 行附近
- `internalMap()` 在第 737 行附近
- `internalMapWrapper()` 在第 863 行附近

Tomcat 的映射顺序你一定要记住：

1. 先按 Host 匹配
2. 再按最长 Context Path 匹配
3. 再按 Servlet 映射规则匹配 Wrapper

Wrapper 匹配规则：

1. 精确匹配
2. 路径前缀匹配
3. 扩展名匹配
4. welcome file
5. default servlet

这就是 Servlet 规范里的映射逻辑在 Tomcat 中的具体实现。

### 9.5 容器 basic valve 如何继续下传

关键类：

- `StandardEngineValve`
- `StandardContextValve`
- `StandardWrapperValve`

建议你重点看：

- `StandardEngineValve.invoke()`
- `StandardContextValve.invoke()`
- `StandardWrapperValve.invoke()`

其中最关键的是 `StandardWrapperValve.invoke()`，因为它完成了：

1. 检查应用和 servlet 可用性
2. `wrapper.allocate()` 获取 servlet 实例
3. `ApplicationFilterFactory.createFilterChain()` 创建过滤器链
4. `filterChain.doFilter()` 触发过滤器和 servlet
5. 请求结束后 `wrapper.deallocate()`


## 10. Filter 和 Servlet 是如何衔接的

关键类：

- `java/org/apache/catalina/core/ApplicationFilterFactory.java`
- `java/org/apache/catalina/core/ApplicationFilterChain.java`

其中：

- `ApplicationFilterFactory` 负责按当前请求和映射规则组装链
- `ApplicationFilterChain` 负责顺序执行 filter，最后调 `servlet.service()`

`ApplicationFilterChain.internalDoFilter()` 的逻辑非常清晰：

1. 如果还有 filter，就执行下一个 filter
2. 如果没有 filter 了，就调用目标 servlet 的 `service()`

所以 Filter 本质上就是 servlet 调用前的一层责任链。


## 11. `Context` 启动阶段到底做了多少事

关键类：

- `java/org/apache/catalina/core/StandardContext.java`
- `java/org/apache/catalina/startup/ContextConfig.java`

### 11.1 `StandardContext.startInternal()` 是 Web 应用启动总控

重点方法：

- `resourcesStart()` 在第 4880 行附近
- `loadOnStartup()` 在第 4934 行附近
- `startInternal()` 在第 4984 行附近
- `listenerStart()` 在第 4674 行附近
- `filterStart()` 在第 4597 行附近

启动顺序大致是：

1. 启动 naming resources
2. 准备 work 目录
3. 初始化 `WebResourceRoot`
4. 启动 resources
5. 初始化并启动 `WebappLoader`
6. 启动 Realm
7. 触发 `CONFIGURE_START_EVENT`
8. 启动子 `Wrapper`
9. 启动 pipeline
10. 创建并启动 `Manager`
11. 初始化 `ServletContainerInitializer`
12. 初始化应用 listener
13. 初始化 filter
14. 加载 `load-on-startup` servlet

这说明：

`Context` 是 Tomcat 中最复杂的容器层对象，因为一个 Web 应用的大部分运行时结构都在这里落地。

### 11.2 `ContextConfig` 负责“把 Web 应用描述信息装配进 Context”

重点方法：

- `configureStart()` 在第 972 行附近
- `webConfig()` 在第 1238 行附近
- `processServletContainerInitializers()` 在第 1835 行附近
- `processAnnotations()` 在第 2145 行附近

它主要做这些事：

1. 读取并合并 `web.xml`
2. 处理 `web-fragment.xml`
3. 扫描 `ServletContainerInitializer`
4. 扫描注解
5. 把解析结果写入 `StandardContext`
6. 配置认证器、JSP、默认 servlet、filter、listener、servlet 映射等

可以简单理解成：

`StandardContext` 负责“启动应用”，`ContextConfig` 负责“装配应用定义”。


## 12. 自动部署是怎么实现的

关键类：

- `java/org/apache/catalina/startup/HostConfig.java`

重点方法：

- `lifecycleEvent()` 在第 297 行附近
- `deployApps()` 在第 468 行附近
- `check()` 在第 1646 行附近

研究 `HostConfig` 时要抓住：

1. 它是 `Host` 的 `LifecycleListener`
2. 在启动时会扫描 `appBase`
3. 在周期事件里会检查变化并自动部署/重部署/卸载

默认部署来源有三种：

1. `conf/Catalina/<host>/*.xml`
2. `webapps/*.war`
3. `webapps/<dir>/`

这也是为什么 Tomcat 很适合做“热部署演示”，因为 Host 层天然就负责这个。


## 13. 类加载体系要怎么读

这一块容易乱，建议分两层：

### 13.1 Tomcat 进程级类加载器

看：

- `Bootstrap`
- `conf/catalina.properties`

结论：

- `commonLoader` 面向容器和应用共享
- `catalinaLoader` 面向 Catalina 内部
- `sharedLoader` 可作为应用共享父加载器

### 13.2 WebApp 级类加载器

看：

- `java/org/apache/catalina/loader/WebappLoader.java`
- `java/org/apache/catalina/loader/WebappClassLoaderBase.java`

研究重点：

1. `WebappLoader.startInternal()` 如何创建 `WebappClassLoaderBase`
2. `WebappClassLoaderBase.modified()` 如何支持热重载
3. `delegate` 为何默认是 `false`
4. Tomcat 如何做内存泄漏清理和线程清理

这里要重点记住一条：

Servlet 规范要求 WebApp 类加载默认优先本地，而不是优先父加载器，所以 `delegate=false` 是故意的。

另外，`WebappClassLoaderBase` 不是一个普通 `URLClassLoader` 包装，它自己实现了很多类加载、资源查找和泄漏清理逻辑。


## 14. Session 管理怎么读

关键类：

- `java/org/apache/catalina/session/ManagerBase.java`
- `java/org/apache/catalina/session/StandardManager.java`
- `java/org/apache/catalina/session/StandardSession.java`

默认情况下，`StandardContext` 在没有显式配置 `Manager` 时，会创建：

- `StandardManager`

`StandardManager` 的特点：

1. 管理内存中的 session
2. 支持重启前后把 session 序列化到 `SESSIONS.ser`
3. 启动时恢复，关闭时持久化

研究入口：

- `StandardContext.startInternal()` 中创建和启动 manager
- `CoyoteAdapter.postParseRequest()` 中解析 session id
- `Request.getSession()` 再往下跟到 manager


## 15. JSP 是怎么接进来的

关键类：

- `java/org/apache/jasper/servlet/JspServlet.java`
- `java/org/apache/jasper/compiler/*`
- `java/org/apache/jasper/runtime/*`

理解方式：

1. `ContextConfig.webConfig()` 会把 JSP 相关配置装进 `Context`
2. JSP 最终仍然以 servlet 方式运行
3. `JspServlet` 负责把 JSP 编译成 Java，再编译成 class，再执行

所以 JSP 在 Tomcat 里不是特殊容器，而是“特殊 servlet”。

如果你要跟调 JSP，请从：

- `JspServlet.init()`
- `JspServlet.serviceJspFile()`
- `JspRuntimeContext`
- `org.apache.jasper.compiler.Compiler`

往下追。


## 16. 建议你按这个顺序读源码

下面这条路线最稳：

### 第一阶段：只建立框架感

1. `conf/server.xml`
2. `Bootstrap`
3. `Catalina`
4. `LifecycleBase`
5. `StandardServer`
6. `StandardService`

目标：

搞清楚 Tomcat 是怎么从配置文件启动起来的。

### 第二阶段：搞清容器树

1. `ContainerBase`
2. `StandardEngine`
3. `StandardHost`
4. `StandardContext`
5. `StandardWrapper`
6. `StandardPipeline`

目标：

搞清楚 Tomcat 为什么要分 `Engine/Host/Context/Wrapper` 这四层。

### 第三阶段：搞清请求流转

1. `Connector`
2. `AbstractProtocol`
3. `Http11NioProtocol`
4. `NioEndpoint`
5. `CoyoteAdapter`
6. `Mapper`
7. `StandardEngineValve`
8. `StandardContextValve`
9. `StandardWrapperValve`
10. `ApplicationFilterChain`

目标：

搞清楚一个请求如何从 socket 一路走到 servlet。

### 第四阶段：搞清 Web 应用启动

1. `ContextConfig`
2. `HostConfig`
3. `WebappLoader`
4. `WebappClassLoaderBase`

目标：

搞清楚 Web 应用如何部署、装配、类加载、热重载。

### 第五阶段：补专题

1. Session
2. JSP
3. Realm/Authenticator
4. WebSocket
5. Cluster


## 17. 建议的打断点方案

如果你打算边跑边读，下面这组断点非常有效。

### 17.1 启动阶段断点

- `Bootstrap.main()`
- `Bootstrap.init()`
- `Bootstrap.load()`
- `Catalina.parseServerXml()`
- `Catalina.createStartDigester()`
- `StandardServer.startInternal()`
- `StandardService.startInternal()`
- `StandardContext.startInternal()`

### 17.2 请求阶段断点

- `Connector.initInternal()`
- `CoyoteAdapter.service()`
- `CoyoteAdapter.postParseRequest()`
- `Mapper.internalMap()`
- `Mapper.internalMapWrapper()`
- `StandardEngineValve.invoke()`
- `StandardContextValve.invoke()`
- `StandardWrapperValve.invoke()`
- `ApplicationFilterChain.internalDoFilter()`
- 你的目标 servlet `service()` / `doGet()` / `doPost()`

### 17.3 应用部署阶段断点

- `HostConfig.deployApps()`
- `ContextConfig.configureStart()`
- `ContextConfig.webConfig()`
- `ContextConfig.processServletContainerInitializers()`
- `ContextConfig.processAnnotations()`
- `WebappLoader.startInternal()`


## 18. 研究时最容易卡住的几个点

### 18.1 容器选择和请求执行不要混在一起

很多人第一次看会误以为：

- `EngineValve` 在算 host
- `ContextValve` 在算 wrapper

其实不是。

真正映射主要在 `Mapper`，容器 valve 更多是在消费 `request` 里已经放好的 `host/context/wrapper`。

### 18.2 `Context` 是最复杂的一层

如果你感觉 `StandardContext` 太大，这是正常的。

因为它同时承担：

- Web 应用配置汇聚
- listener/filter/servlet 生命周期
- 资源系统
- 类加载器
- Session manager
- 注解扫描结果挂载

所以不要试图一口气吃完，按主题拆开读。

### 18.3 `server.xml` 只负责 Catalina 级对象，不负责应用内全部细节

`server.xml` 主要管：

- Server
- Service
- Connector
- Engine
- Host
- 部分 Context

Web 应用内部的大量配置，真正来源还是：

- `web.xml`
- `web-fragment.xml`
- 注解
- SCI


## 19. 一条适合你自己跟读的实战路线

如果你想真正“跑通一次”，我建议你这样做：

1. 先只读 `server.xml`，手画对象树
2. 从 `Bootstrap.main()` 跟到 `Catalina.parseServerXml()`
3. 确认 `StandardServer -> StandardService -> StandardEngine -> StandardHost` 都被创建出来
4. 看 `StandardContext.startInternal()` 如何启动一个 Web 应用
5. 发一个最简单的 HTTP 请求到 `ROOT/index.jsp` 或 `examples`
6. 从 `CoyoteAdapter.service()` 一路跟到 `ApplicationFilterChain.internalDoFilter()`
7. 再回头单独研究 `Mapper`
8. 最后补 `ContextConfig`、`WebappLoader`、`StandardManager`、`JspServlet`

这样你会形成两个闭环：

- 启动闭环
- 请求闭环

Tomcat 基本就真正入门了。


## 20. 推荐你边读边回答这 10 个问题

1. 为什么 `Server` 和 `Service` 不是 `Container`？
2. 为什么 `Service` 要把 `Connector` 和 `Engine` 放在一起？
3. `Mapper` 为什么不放在 `EngineValve` 里做？
4. 为什么 `Context` 启动比 `Host` 和 `Wrapper` 重很多？
5. 为什么 WebApp 类加载默认不是标准双亲委派优先？
6. `load-on-startup` servlet 是在什么时候真正实例化的？
7. `FilterChain` 是什么时候组装出来的？
8. 一个请求的 session id 是在哪个阶段解析的？
9. 自动部署为什么放在 `HostConfig`，而不是 `ContextConfig`？
10. JSP 为什么最终仍然表现为 servlet？

如果这 10 个问题你都能结合源码回答，Tomcat 这份源码你就已经读进去了。


## 21. 最后给你的阅读建议

研究 Tomcat，不建议一开始就看“某个功能点”，建议优先看“骨架”。

推荐优先级：

1. 启动骨架
2. 生命周期骨架
3. 容器骨架
4. 请求骨架
5. 应用装配骨架
6. 横向专题

如果你后面愿意，我可以继续基于这份文档，给你再拆出 3 份更细的配套材料：

1. `启动源码精读版`
2. `请求处理链路精读版`
3. `Web 应用加载与类加载器精读版`

这样你就可以按专题继续深挖，而不是重复在大类里迷路。
