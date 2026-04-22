# Tomcat 9.0.56 启动与请求主线阅读地图

这份文档是给“跟源码 + 打断点”用的，不追求面面俱到，只抓最核心主干。


## 1. 先建立一张总图

Tomcat 可以先粗分成两大半：

1. 启动半程
   负责读取 `server.xml`，把 XML 变成运行时对象图，并按生命周期启动。
2. 请求半程
   负责监听端口、解析 HTTP、做容器映射，并最终调用 Filter / Servlet。

把这两条主线接起来，就是：

`Bootstrap`
-> `Catalina`
-> `StandardServer`
-> `StandardService`
-> `Connector`
-> `ProtocolHandler / Endpoint`
-> `CoyoteAdapter`
-> `Engine/Host/Context/Wrapper`
-> `FilterChain`
-> `Servlet`


## 2. 启动主线怎么读

建议第一遍只盯下面这些类：

1. [java/org/apache/catalina/startup/Bootstrap.java](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/startup/Bootstrap.java)
2. [java/org/apache/catalina/startup/Catalina.java](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/startup/Catalina.java)
3. [conf/server.xml](/Users/lzr/sources/tomcat/tomcat-9.0.56/conf/server.xml)
4. [java/org/apache/catalina/core/StandardServer.java](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/core/StandardServer.java)
5. [java/org/apache/catalina/core/StandardService.java](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/core/StandardService.java)
6. [java/org/apache/catalina/connector/Connector.java](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/connector/Connector.java)

### 2.1 启动顺序先背下来

`Bootstrap.main()`
-> `Bootstrap.init()`
-> `Bootstrap.load(args)`
-> `Catalina.load()`
-> `Catalina.parseServerXml(true)`
-> `StandardServer.init()`
-> `Bootstrap.start()`
-> `Catalina.start()`
-> `StandardServer.start()`
-> `StandardService.start()`
-> `Engine.start()`
-> `Host.start()`
-> `Context.start()`
-> `Wrapper.start()`

### 2.2 每一层在干什么

- `Bootstrap`
  只负责“引导”。重点是类加载器和反射调用 `Catalina`。
- `Catalina`
  负责“把配置变成对象图”。重点是 `Digester`、`server.xml`、`load/start/stop`。
- `StandardServer`
  整个 Tomcat 实例。管理多个 `Service`，同时持有全局命名资源、关闭端口监听等。
- `StandardService`
  把“一个 `Engine` + 一组 `Connector`”绑在一起。
- `Connector`
  把 Coyote 协议层接入 Catalina 容器层。

### 2.3 启动时最值得下断点的位置

- [java/org/apache/catalina/startup/Bootstrap.java:506](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/startup/Bootstrap.java#L506)
  `main()`，看命令如何分流到 `load/start/stop`
- [java/org/apache/catalina/startup/Bootstrap.java:297](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/startup/Bootstrap.java#L297)
  `init()`，看 `commonLoader/catalinaLoader/sharedLoader`
- [java/org/apache/catalina/startup/Catalina.java:745](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/startup/Catalina.java#L745)
  `load()`，看 server 初始化顺序
- [java/org/apache/catalina/startup/Catalina.java:589](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/startup/Catalina.java#L589)
  `parseServerXml()`，看 XML 到对象图
- [java/org/apache/catalina/startup/Catalina.java:380](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/startup/Catalina.java#L380)
  `createStartDigester()`，看规则如何映射到 `StandardServer/Service/Connector/...`
- [java/org/apache/catalina/core/StandardServer.java:1022](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/core/StandardServer.java#L1022)
  `initInternal()`，看全局线程池、命名资源、Service 初始化
- [java/org/apache/catalina/core/StandardServer.java:940](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/core/StandardServer.java#L940)
  `startInternal()`，看 Server 如何驱动各个 Service 启动
- [java/org/apache/catalina/core/StandardService.java:540](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/core/StandardService.java#L540)
  `initInternal()`，看 Engine / Connector / MapperListener 初始化
- [java/org/apache/catalina/core/StandardService.java:424](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/core/StandardService.java#L424)
  `startInternal()`，看启动顺序为什么是 Engine -> Executors -> MapperListener -> Connectors
- [java/org/apache/catalina/connector/Connector.java:1013](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/connector/Connector.java#L1013)
  `initInternal()`，看 `CoyoteAdapter` 是怎么挂上去的
- [java/org/apache/catalina/connector/Connector.java:1075](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/connector/Connector.java#L1075)
  `startInternal()`，看请求能力从哪里真正打开


## 3. 请求主线怎么读

建议第二遍盯下面这些类：

1. [java/org/apache/coyote/http11/Http11Processor.java](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/coyote/http11/Http11Processor.java)
2. [java/org/apache/coyote/AbstractProtocol.java](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/coyote/AbstractProtocol.java)
3. [java/org/apache/catalina/connector/CoyoteAdapter.java](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/connector/CoyoteAdapter.java)
4. [java/org/apache/catalina/core/StandardEngineValve.java](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/core/StandardEngineValve.java)
5. [java/org/apache/catalina/core/StandardHostValve.java](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/core/StandardHostValve.java)
6. [java/org/apache/catalina/core/StandardContextValve.java](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/core/StandardContextValve.java)
7. [java/org/apache/catalina/core/StandardWrapperValve.java](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/core/StandardWrapperValve.java)
8. [java/org/apache/catalina/core/ApplicationFilterChain.java](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/core/ApplicationFilterChain.java)

### 3.1 请求主链先背下来

`Http11Processor.service()`
-> `CoyoteAdapter.service()`
-> `CoyoteAdapter.postParseRequest()`
-> `Mapper.map()`
-> `StandardEngineValve.invoke()`
-> `StandardHostValve.invoke()`
-> `StandardContextValve.invoke()`
-> `StandardWrapperValve.invoke()`
-> `ApplicationFilterChain.doFilter()`
-> `Servlet.service()`

### 3.2 每一层在干什么

- `Http11Processor`
  负责一次 HTTP/1.1 请求的协议处理主循环。
- `CoyoteAdapter`
  负责把 `org.apache.coyote.Request/Response` 桥接成 Catalina 的 `Request/Response`。
- `postParseRequest()`
  负责 URI 解码、规范化、Mapper 映射、SessionId 解析。这是请求主线里最关键的方法之一。
- `StandardEngineValve`
  根据映射结果选中 `Host`。
- `StandardHostValve`
  进入具体 `Context`，绑定 WebApp 类加载器，处理 request listener 和错误页。
- `StandardContextValve`
  选中 `Wrapper`，挡掉对 `WEB-INF/META-INF` 的直接访问。
- `StandardWrapperValve`
  分配 Servlet、创建 FilterChain、最终调用业务代码。
- `ApplicationFilterChain`
  负责把所有 Filter 串起来，最后落到 `servlet.service()`。

### 3.3 请求阶段最值得下断点的位置

- [java/org/apache/coyote/http11/Http11Processor.java:249](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/coyote/http11/Http11Processor.java#L249)
  `service()`，看 keep-alive 主循环
- [java/org/apache/catalina/connector/CoyoteAdapter.java:316](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/connector/CoyoteAdapter.java#L316)
  `service()`，看请求何时进入 Catalina
- [java/org/apache/catalina/connector/CoyoteAdapter.java:583](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/connector/CoyoteAdapter.java#L583)
  `postParseRequest()`，看 URI、Mapper、Session 的准备过程
- [java/org/apache/catalina/core/StandardEngineValve.java:59](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/core/StandardEngineValve.java#L59)
  看 `request.getHost()` 是什么时候已经准备好的
- [java/org/apache/catalina/core/StandardHostValve.java:99](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/core/StandardHostValve.java#L99)
  看 WebApp 类加载器绑定和错误页处理
- [java/org/apache/catalina/core/StandardContextValve.java:63](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/core/StandardContextValve.java#L63)
  看 `Wrapper` 选择后的最后一跳
- [java/org/apache/catalina/core/StandardWrapperValve.java:87](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/core/StandardWrapperValve.java#L87)
  看 FilterChain 和 Servlet 是怎么被调用的
- [java/org/apache/catalina/core/ApplicationFilterChain.java:127](/Users/lzr/sources/tomcat/tomcat-9.0.56/java/org/apache/catalina/core/ApplicationFilterChain.java#L127)
  `internalDoFilter()`，看 Filter 和 Servlet 的真正调用顺序


## 4. 读源码时重点盯哪些对象

启动阶段重点观察：

- `Bootstrap.daemon`
- `Catalina.server`
- `StandardServer.services`
- `StandardService.engine`
- `StandardService.connectors`
- `Connector.protocolHandler`

请求阶段重点观察：

- `org.apache.coyote.Request`
- `org.apache.catalina.connector.Request`
- `request.mappingData`
- `request.getHost() / getContext() / getWrapper()`
- `ApplicationFilterChain.pos / n`
- `StandardWrapperValve.wrapper`


## 5. 一个最顺手的阅读顺序

如果你是第一次系统读 Tomcat，我建议按这个顺序：

1. 先读 [TOMCAT-9.0.56-STUDY-GUIDE.md](/Users/lzr/sources/tomcat/tomcat-9.0.56/TOMCAT-9.0.56-STUDY-GUIDE.md)
2. 再读 [TOMCAT-9.0.56-STARTUP-DEEP-DIVE.md](/Users/lzr/sources/tomcat/tomcat-9.0.56/TOMCAT-9.0.56-STARTUP-DEEP-DIVE.md)
3. 然后对着 [conf/server.xml](/Users/lzr/sources/tomcat/tomcat-9.0.56/conf/server.xml) 看 `Catalina.createStartDigester()`
4. 再读 [TOMCAT-9.0.56-REQUEST-PIPELINE-DEEP-DIVE.md](/Users/lzr/sources/tomcat/tomcat-9.0.56/TOMCAT-9.0.56-REQUEST-PIPELINE-DEEP-DIVE.md)
5. 最后带着断点走 `Http11Processor -> CoyoteAdapter -> Valve -> FilterChain -> Servlet`

这个顺序的好处是：

- 第一遍先建立地图，不纠结细节
- 第二遍再跟主线，不容易迷路
- 第三遍进专题，比如类加载、部署、Session、JSP


## 6. 关于“逐行注释”的建议

Tomcat 整个项目很大，不适合真的全项目逐行加中文注释，否则后面会被注释淹没。

更高收益的做法是：

1. 先把主干链路注释透
2. 再按专题补局部注释
3. 用文档 + 断点 + 调用链一起读

这次建议你优先读的，已经是最影响理解的那一小撮代码。
