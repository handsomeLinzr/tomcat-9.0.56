# Tomcat 9.0.56 请求处理链路精读

这是一份只讲请求处理主线的专题文档，建议配合另外两份一起看：

- [总览文档](./TOMCAT-9.0.56-STUDY-GUIDE.md)
- [启动源码精读](./TOMCAT-9.0.56-STARTUP-DEEP-DIVE.md)

目标只有一个：

把“一个 HTTP 请求如何从 socket 走到 servlet”真正跟通一次。


## 1. 先背下请求主链

Tomcat 默认 HTTP 请求主线可以先记成这一条：

`NioEndpoint`
-> `AbstractProtocol.ConnectionHandler`
-> `Processor`
-> `CoyoteAdapter.service()`
-> `postParseRequest()`
-> `Mapper.map()`
-> `StandardEngineValve`
-> `StandardHostValve`
-> `StandardContextValve`
-> `StandardWrapperValve`
-> `ApplicationFilterFactory`
-> `ApplicationFilterChain`
-> `Servlet.service()`

如果中途被重定向、报错、异步挂起，路径会拐弯，但主骨架还是这条。


## 2. 先把三套对象分清

研究请求链最容易乱，是因为 Tomcat 同时有三套世界：

1. 网络/协议世界
   `ProtocolHandler`、`Endpoint`、`Processor`
2. Catalina 请求世界
   `org.apache.catalina.connector.Request/Response`
3. Servlet API 世界
   `HttpServletRequest/HttpServletResponse`

研究时一定要分清：

- socket 是哪一层的
- request 是哪一层的
- 什么时候从 Coyote Request 包装成 Catalina Request
- 什么时候再暴露成 ServletRequest


## 3. 连接是从哪里进来的

关键类：

- `java/org/apache/coyote/http11/Http11NioProtocol.java`
- `java/org/apache/tomcat/util/net/NioEndpoint.java`
- `java/org/apache/coyote/AbstractProtocol.java`

### 3.1 `Http11NioProtocol`

默认 `Connector` 的 `protocol="HTTP/1.1"` 会创建：

- `Http11NioProtocol`

它本身很薄，核心就是：

- 继承 `AbstractHttp11JsseProtocol`
- 底层 endpoint 用 `NioEndpoint`

### 3.2 `NioEndpoint`

`NioEndpoint` 负责：

1. 绑定监听端口
2. Acceptor 接收连接
3. Poller 监听 read/write 事件
4. 把事件交给工作线程处理

你可以简单理解成：

- `NioEndpoint` 负责“把 socket 活动组织起来”

### 3.3 `AbstractProtocol.ConnectionHandler.process()`

这是网络事件进入协议处理的关键节点。

它会：

1. 根据 socket 找当前 `Processor`
2. 没有就新建或复用一个
3. 把 `SocketEvent` 交给 `Processor`
4. 根据返回状态决定连接是否保持、升级或关闭

所以：

- 连接复用
- keep-alive
- processor 缓存

这些能力的核心都在这里附近。


## 4. `Connector` 如何把 Coyote 接到 Catalina

关键类：

- `java/org/apache/catalina/connector/Connector.java`

### 4.1 初始化阶段

`Connector.initInternal()` 会做：

1. 创建 `CoyoteAdapter`
2. `protocolHandler.setAdapter(adapter)`
3. 调 `protocolHandler.init()`

这一刻起：

- Coyote 协议层收到请求后
- 就知道该回调哪个 adapter 进入 Catalina

### 4.2 启动阶段

`Connector.startInternal()` 做得很直接：

- 调 `protocolHandler.start()`

从这里开始，外部连接就可以真正进 Tomcat 了。


## 5. `CoyoteAdapter.service()` 是请求进入 Catalina 的第一站

关键类：

- `java/org/apache/catalina/connector/CoyoteAdapter.java`

重点方法：

- `service()`
- `postParseRequest()`

### 5.1 `service()` 先做对象桥接

第一次进入时，它会：

1. 创建 Catalina `Request`
2. 创建 Catalina `Response`
3. 分别挂到 Coyote `Request/Response` 的 note 上
4. request/response 互相关联

这一步很关键：

Tomcat 后面几乎所有容器逻辑都用的是 Catalina 的 `Request/Response`。

### 5.2 `service()` 的主流程

可以概括成：

1. `postParseRequest()`
2. 设置 asyncSupported
3. 从 Engine pipeline 开始调用
4. 请求结束时 `finishRequest()` / `finishResponse()`
5. 写 access log
6. recycle request/response

也就是说：

`service()` 真正像一个“总调度入口”。


## 6. `postParseRequest()` 是最值得精读的方法之一

重点位置：

- `CoyoteAdapter.postParseRequest()` 在 581 行附近

这一段建议至少读两遍。

### 6.1 它做了哪些事情

大致顺序：

1. 处理 scheme 和 secure
2. 处理 `proxyName` / `proxyPort`
3. 读取和处理原始 URI
4. 解析 path parameter
5. `%xx` 解码
6. URI 规范化
7. 选择 serverName
8. 调 `Mapper.map()`
9. 解析 URL session id
10. 解析 Cookie session id
11. 解析 SSL session id
12. 必要时按 session 版本做二次映射
13. 处理 redirect
14. 拦截 TRACE 等特殊方法

### 6.2 为什么它重要

因为这里决定了：

- 这个请求是否是合法请求
- 这个请求对应哪个 host/context/wrapper
- 这个请求有没有 session
- 这个请求是否需要重定向

后面的容器基本都是在消费这里的结果。


## 7. `Mapper` 如何把请求映射到容器对象

关键类：

- `java/org/apache/catalina/mapper/Mapper.java`
- `java/org/apache/catalina/mapper/MapperListener.java`

### 7.1 先理解 `MapperListener`

`MapperListener` 在启动期就把运行时容器信息注册进 `Mapper`：

1. 注册 Host
2. 注册 Context
3. 注册 Wrapper
4. 监听后续动态增删变化

所以请求时：

- `Mapper` 不需要遍历整个容器树
- 它直接查自己维护好的结构化映射表

### 7.2 `Mapper.map()` 的三段匹配

请求匹配顺序是：

1. Host 匹配
2. Context 匹配
3. Wrapper 匹配

#### 7.2.1 Host 匹配

`internalMap()` 里先按 host 查：

- 精确 host
- 通配 host
- default host

结果写入：

- `mappingData.host`

#### 7.2.2 Context 匹配

然后按最长上下文路径匹配：

- `/app`
- `/app/admin`
- `/`

结果写入：

- `mappingData.context`
- `mappingData.contextPath`

#### 7.2.3 Wrapper 匹配

`internalMapWrapper()` 是 servlet 映射核心：

1. 精确匹配
2. 前缀匹配
3. 扩展名匹配
4. welcome file
5. default servlet

结果写入：

- `mappingData.wrapper`
- `mappingData.wrapperPath`
- `mappingData.pathInfo`
- `mappingData.matchType`

### 7.3 非常关键的一点

`Engine/Host/Context` 的 basic valve 不负责重新计算映射。

它们大多数时候只是使用：

- `request.getHost()`
- `request.getContext()`
- `request.getWrapper()`

这些已经在 `Mapper` 阶段准备好的结果。


## 8. 容器责任链如何一层层往下传

Tomcat 容器默认责任链入口是：

- `connector.getService().getContainer().getPipeline().getFirst().invoke(...)`

这个 `container` 就是 `Engine`。

### 8.1 `StandardEngineValve`

关键类：

- `java/org/apache/catalina/core/StandardEngineValve.java`

作用：

1. 读 `request.getHost()`
2. 检查 host 是否存在
3. 继续调用 `host.getPipeline().getFirst().invoke()`

所以它本质上只是把请求从 Engine 继续转给 Host。

### 8.2 `StandardHostValve`

关键类：

- `java/org/apache/catalina/core/StandardHostValve.java`

这是非常重要的一层，因为它除了下传，还负责：

1. 取 `request.getContext()`
2. 绑定当前 WebApp 类加载器
3. 触发 `ServletRequestListener` 的 request init
4. 调用 Context pipeline
5. 处理应用级错误页
6. 触发 request destroy
7. 最终 unbind 类加载器

所以 Host 层是：

- 请求进入具体 Web 应用的边界层

### 8.3 `StandardContextValve`

关键类：

- `java/org/apache/catalina/core/StandardContextValve.java`

作用：

1. 拒绝直接访问 `/WEB-INF` 和 `/META-INF`
2. 读 `request.getWrapper()`
3. 如果没有 wrapper，直接 404
4. 发送 100-continue acknowledgement
5. 调用 wrapper pipeline

所以 Context 层更像：

- 应用内资源访问控制
- 把请求正式交给目标 servlet 包装器

### 8.4 `StandardWrapperValve`

关键类：

- `java/org/apache/catalina/core/StandardWrapperValve.java`

这是 servlet 执行前最后一层，也是最核心的一层。

它负责：

1. 检查 `Context` 是否可用
2. 检查 `Wrapper` 是否可用
3. `wrapper.allocate()` 分配 servlet 实例
4. `ApplicationFilterFactory.createFilterChain()` 创建过滤器链
5. `filterChain.doFilter()`
6. 捕获业务异常并设置错误状态
7. `wrapper.deallocate()` 归还 servlet 实例
8. 必要时 `wrapper.unload()`


## 9. `Wrapper.allocate()` 到底干了什么

关键类：

- `java/org/apache/catalina/core/StandardWrapper.java`

重点方法：

- `allocate()`
- `load()`
- `loadServlet()`
- `initServlet()`

### 9.1 普通 servlet 情况

大致逻辑：

1. 如果实例还没创建，就 `loadServlet()`
2. 如果还没 init，就 `initServlet()`
3. 返回实例

### 9.2 `loadServlet()` 的关键动作

1. 用 `InstanceManager` 创建 servlet 实例
2. 处理 `@MultipartConfig`
3. 处理 `ContainerServlet`
4. 调 `initServlet()`
5. 触发 `"load"` 容器事件

### 9.3 为什么要经过 `Wrapper`

因为 Tomcat 不能直接 new servlet 就调用，它还要统一处理：

- servlet 生命周期
- 单实例复用
- `load-on-startup`
- 不可用状态
- JMX 统计
- 特殊旧规范兼容


## 10. FilterChain 是什么时候组装出来的

关键类：

- `java/org/apache/catalina/core/ApplicationFilterFactory.java`
- `java/org/apache/catalina/core/ApplicationFilterChain.java`

### 10.1 创建时机

在：

- `StandardWrapperValve.invoke()`

里，通过：

- `ApplicationFilterFactory.createFilterChain(request, wrapper, servlet)`

动态创建。

也就是说：

- FilterChain 不是应用启动时预先为每个请求固定好
- 而是每次请求按当前 dispatch 类型、URL、servletName 现组装

### 10.2 组装规则

`ApplicationFilterFactory` 会：

1. 读取 `context.findFilterMaps()`
2. 先加 URL 匹配的 filter
3. 再加 servlet-name 匹配的 filter
4. 同时检查 dispatcher 类型

所以 filter 顺序不是随便来的，而是严格按规范和映射顺序构造出来的。

### 10.3 `ApplicationFilterChain.internalDoFilter()`

核心逻辑非常纯粹：

1. `pos < n` 时执行下一个 filter
2. 所有 filter 执行完后，调用 `servlet.service()`

这就是最经典的责任链模式。


## 11. 一个请求真正进入业务代码的瞬间

如果你问：

- “Tomcat 最终在哪一行真正调用了我的 servlet？”

最值得看的位置是：

- `ApplicationFilterChain.internalDoFilter()`
- `servlet.service(request, response)`

再往后就是：

- `HttpServlet.service()`
- `doGet()` / `doPost()` / 你的业务方法

所以很多时候跟调 Tomcat 到这里就可以切回业务代码了。


## 12. 错误页是在哪一层处理的

重点类：

- `StandardHostValve`

错误处理的核心思路是：

1. 下游处理出错时把异常挂到 request 属性
2. response 标记 error 状态
3. 回到 Host 层后查找应用定义的 error page
4. 如果有，就转发到对应 error page

所以：

- Tomcat 的应用级错误页处理主要发生在 Host 层，而不是 Wrapper 层


## 13. 异步请求在主链里是怎么分叉的

关键类：

- `CoyoteAdapter`
- `AsyncContextImpl`

### 13.1 同步请求

同步请求的典型结尾是：

1. `finishRequest()`
2. `finishResponse()`
3. access log
4. recycle

### 13.2 异步请求

异步请求会在这些地方分叉：

- `request.isAsync()`
- `request.isAsyncDispatching()`
- `CoyoteAdapter.asyncDispatch()`

异步时：

1. 容器线程不会立刻把请求彻底收尾
2. 后续事件可能由新的 `SocketEvent` 驱动
3. 再次进入 `asyncDispatch()`
4. 然后重新走到容器 pipeline

所以异步请求不是“另一个模型”，而是“同一模型被切成多段重新进入”。


## 14. AccessLog 和 recycle 在哪一步发生

仍然重点看：

- `CoyoteAdapter.service()`
- `CoyoteAdapter.asyncDispatch()`

请求执行完后会：

1. 计算耗时
2. 调 context/host/engine 的 access log
3. 更新 wrapper error count
4. `request.recycle()`
5. `response.recycle()`

这里要注意：

容器责任链本身不负责 recycle，真正 recycle 多半发生在 adapter 回收阶段。


## 15. 最推荐的断点顺序

如果你要跟一次真实请求，建议按这组断点打：

1. `Connector.initInternal()`
2. `AbstractProtocol.start()`
3. `AbstractProtocol.ConnectionHandler.process()`
4. `CoyoteAdapter.service()`
5. `CoyoteAdapter.postParseRequest()`
6. `Mapper.internalMap()`
7. `Mapper.internalMapWrapper()`
8. `StandardEngineValve.invoke()`
9. `StandardHostValve.invoke()`
10. `StandardContextValve.invoke()`
11. `StandardWrapperValve.invoke()`
12. `StandardWrapper.allocate()`
13. `ApplicationFilterFactory.createFilterChain()`
14. `ApplicationFilterChain.internalDoFilter()`
15. 你的 servlet `service()/doGet()/doPost()`

如果你想看错误页流程，再额外打：

1. `StandardHostValve.status()`
2. `StandardHostValve.throwable()`

如果你想看异步流程，再额外打：

1. `CoyoteAdapter.asyncDispatch()`
2. `AsyncContextImpl.doInternalDispatch()`


## 16. 建议你用什么请求做第一次跟调

推荐三类请求，按难度递增：

### 16.1 最简单静态资源

例如：

- `GET /`
- `GET /tomcat.css`

适合先观察：

- `Mapper`
- default servlet
- Context/Wrapper 基本分发

### 16.2 普通 servlet 或 examples 应用

例如：

- `GET /examples/servlets/servlet/RequestInfoExample`

适合观察：

- servlet 映射
- filter chain
- wrapper allocate/service/deallocate

### 16.3 JSP 请求

例如：

- `GET /index.jsp`
- `GET /examples/jsp/...`

适合观察：

- JSP servlet
- Jasper 编译与执行


## 17. 跟读时建议回答这几个问题

1. `Mapper` 为什么要独立存在，而不是在 valve 里逐层现找？
2. `Request` 在哪一刻从 Coyote 对象变成 Catalina 对象？
3. 为什么 `StandardHostValve` 要负责绑定类加载器？
4. FilterChain 为什么是请求时现组装？
5. servlet 实例为什么要通过 `Wrapper.allocate()` 而不是直接调用？
6. 错误页为什么主要在 Host 层处理？
7. 异步请求为什么会重新进入容器 pipeline？
8. recycle 为什么放在 adapter 末尾而不是 servlet 末尾？

这 8 个问题能答顺，请求主链就算真正吃透了。


## 18. 和启动主线怎么接起来

请求链和启动链在两个地方正式接上：

1. `Connector.initInternal()`
   启动时把 `CoyoteAdapter` 绑进去
2. `MapperListener.startInternal()`
   启动时把容器信息注册进 `Mapper`

也就是说，请求处理能跑起来，依赖启动阶段先完成两件事：

1. 接入点已经建立
2. 映射表已经建立

如果这两件事没完成，请求主链根本跑不通。


## 19. 下一步继续怎么深挖

跟完这份文档后，最适合继续深挖的三个方向是：

1. `StandardContext` 与 `ContextConfig`
   研究一个 Web 应用在启动时如何装配 filter/listener/servlet
2. `WebappLoader` 与 `WebappClassLoaderBase`
   研究 WebApp 类加载隔离和热重载
3. `JspServlet` 与 Jasper
   研究 JSP 如何编译执行

如果你愿意，我下一步可以继续给你补第三份专题：

- `Tomcat Web 应用加载与类加载器精读`
