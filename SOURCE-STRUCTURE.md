# Tomcat 9.0.56 源码结构导读

本文档帮助快速建立 Tomcat 源码的“地图”，方便按模块、按请求链路阅读。

---

## 一、顶层目录一览

```
tomcat-9.0.56/
├── bin/                    # 启动/关闭脚本（catalina.sh、startup.sh 等）
├── conf/                   # 默认配置：server.xml、web.xml、logging 等
├── java/                   # ★ 核心 Java 源码（见下文包结构）
├── modules/                # 可选模块：jdbc-pool、cxf、owb
├── res/                    # 构建/资源：bnd、checkstyle、脚本、Maven 等
├── test/                   # 单元测试与测试用 webapp
├── webapps/                # 默认应用：ROOT、docs、examples、manager、host-manager
├── build.xml               # Ant 构建主脚本
├── build.properties.default
└── BUILDING.txt / RUNNING.txt
```

**阅读源码时重点看：`java/`、`conf/`（理解 server.xml 与容器对应关系）。**

---

## 二、Java 源码包结构（java/）

### 2.1 两大块：规范 API vs 实现

| 位置 | 说明 |
|------|------|
| `java/javax/*` | **规范 API**：Servlet、JSP、EL、WebSocket、JASPIC 等接口/抽象类（javax 包名） |
| `java/org/apache/*` | **Tomcat 实现**：Catalina 容器、Coyote 连接器、Jasper、EL 实现等 |

### 2.2 核心实现包（org.apache）总览

```
org.apache
├── catalina/          # 容器与生命周期（Server/Service/Engine/Host/Context/Wrapper）
├── coyote/             # 协议层：HTTP/AJP 解析、Processor、Adapter 入口
├── jasper/             # JSP 编译与运行（编译器、运行时、EL 在 JSP 中的集成）
├── el/                 # EL 表达式引擎（独立于 JSP）
├── naming/             # JNDI 与资源绑定
├── juli/               # 日志
├── tomcat/             # 工具与通用组件：util、digester、net、dbcp 等
└── tools/              # 构建相关
```

---

## 三、启动流程与入口（先看这两处）

### 3.1 入口类

| 类 | 作用 | 路径 |
|----|------|------|
| **Bootstrap** | 主入口：构造 ClassLoader，反射调用 Catalina | `catalina/startup/Bootstrap.java` |
| **Catalina** | 解析 `server.xml`，创建 Server→Service→Engine→Host，启动/停止 | `catalina/startup/Catalina.java` |

**阅读顺序建议：**  
`Bootstrap.main()` → 加载 Catalina → `Catalina.load()`（Digester 解析 server.xml）→ `Catalina.start()`（Lifecycle 启动整条链）。

### 3.2 与 server.xml 的对应关系

`conf/server.xml` 结构大致如下（与类一一对应）：

```xml
<Server>                          → org.apache.catalina.Server 实现
  <Listener ... />                → 各种 LifecycleListener
  <GlobalNamingResources>         → JNDI 全局资源
  <Service name="Catalina">       → org.apache.catalina.Service（如 StandardService）
    <Connector port="8080" .../>  → org.apache.catalina.connector.Connector（内部用 Coyote）
    <Engine name="Catalina">      → org.apache.catalina.Engine（如 StandardEngine）
      <Realm ... />               → 认证/授权
      <Host name="localhost">     → org.apache.catalina.Host（如 StandardHost）
        <Valve ... />             → 如 AccessLogValve
        <!-- Context 多由部署生成，不常写在 server.xml -->
      </Host>
    </Engine>
  </Service>
</Server>
```

理解 **Server → Service → Engine → Host → Context → Wrapper** 的层级，对读容器和 Valve 链非常有帮助。

---

## 四、请求处理链路（从收到请求到 Servlet）

一条 HTTP 请求的大致路径（便于按“请求流”读代码）：

```
1. Coyote（协议层）
   Socket → 协议解析（HTTP/1.1、AJP 等）→ 生成 org.apache.coyote.Request/Response
   位置：org.apache.coyote.*、coyote.http11.*、coyote.ajp.*

2. 进入 Catalina（容器层）
   Coyote 调用 Adapter，将 Coyote Request 转成 Servlet 可用的 Request
   关键类：org.apache.catalina.connector.CoyoteAdapter
   产出：org.apache.catalina.connector.Request / Response（实现 ServletRequest/Response）

3. 容器管道（Pipeline + Valve）
   每个 Container（Engine/Host/Context/Wrapper）有一条 Pipeline，由若干 Valve 组成
   顺序：EngineValve → HostValve → ContextValve → WrapperValve
   关键类：
   - org.apache.catalina.core.StandardEngineValve
   - org.apache.catalina.core.StandardHostValve
   - org.apache.catalina.core.StandardContextValve
   - org.apache.catalina.core.StandardWrapperValve

4. 最终执行 Servlet
   StandardWrapperValve 里：加载 Servlet、调用 service()
   关键类：org.apache.catalina.core.StandardWrapper
```

**建议阅读顺序：**  
`Connector`（如何把 Coyote 和 Container 绑在一起）→ `CoyoteAdapter.service()` → 各层 `*Valve.invoke()` → `StandardWrapperValve` / `StandardWrapper`。

---

## 五、容器与核心接口（按层级看）

### 5.1 生命周期与容器接口

- **Lifecycle**：`org.apache.catalina.Lifecycle`  
  所有可启动/停止的组件都实现它（Server、Service、Engine、Host、Context、Wrapper、Connector 等）。
- **Container**：`org.apache.catalina.Container`  
  能处理请求的组件，且可带子容器，形成树形结构。

### 5.2 四层容器（由上到下）

| 层级 | 接口 | 默认实现 | 说明 |
|------|------|----------|------|
| 顶层 | Engine | StandardEngine | 代表整个 Servlet 引擎，按 Host 分发 |
| 虚拟主机 | Host | StandardHost | 按主机名选 Context |
| 应用 | Context | StandardContext | 一个 web 应用，对应 ServletContext |
| Servlet | Wrapper | StandardWrapper | 一个 Servlet 定义与实例管理 |

实现类都在 `org.apache.catalina.core` 包下。  
**StandardContext**（约 6800+ 行）和 **StandardWrapper**（约 1800 行）是单文件体量最大的两个，可结合“请求如何路由到 Servlet”分段看。

### 5.3 连接器（Connector）

- **Connector**：`org.apache.catalina.connector.Connector`  
  绑定协议（Coyote）与容器（Service）；内部持有 `ProtocolHandler`（如 Http11NioProtocol）。
- **CoyoteAdapter**：`org.apache.catalina.connector.CoyoteAdapter`  
  实现 Coyote 的 `Adapter`，把 Coyote Request/Response 转成 Catalina 的 Request/Response 并调用容器管道。

---

## 六、其他重要模块速览

### 6.1 Jasper（JSP）

- **位置**：`org.apache.jasper.*`
- **作用**：JSP 编译为 Servlet、JSP 运行时、标签与 EL 在 JSP 中的使用。
- **入口**：可先看 `JspServlet`、编译相关类（如 `JspCompilationContext`）、EL 在 JSP 中的集成。

### 6.2 EL（表达式语言）

- **位置**：`org.apache.el.*`（EL 引擎）、`org.apache.jasper.el`（JSP 与 EL 桥接）
- **作用**：解析与执行 `${...}` 表达式。

### 6.3 认证与安全

- **位置**：`org.apache.catalina.authenticator.*`、`org.apache.catalina.realm.*`
- **作用**：BASIC/DIGEST/FORM/CLIENT-CERT 等认证、Realm 抽象（UserDatabaseRealm、JDBCRealm 等）。

### 6.4 集群与会话

- **位置**：`org.apache.catalina.ha.*`、`org.apache.catalina.tribes.*`、`org.apache.catalina.session.*`
- **作用**：集群通信、会话复制、Session 管理。

### 6.5 工具与通用

- **位置**：`org.apache.tomcat.util.*`  
  常用：`digester`（解析 XML）、`net`（Socket/NIO）、`buf`（ByteChunk 等）、`http`（Cookie、解析等）。

---

## 七、建议阅读顺序（按目标）

### 目标：理解“从启动到能处理请求”

1. `Bootstrap.java`（main、ClassLoader、调用 Catalina）
2. `Catalina.java`（load/start、server.xml、Digester）
3. `conf/server.xml`（对照 Server/Service/Engine/Host/Connector）
4. `StandardService`、`Connector`、`CoyoteAdapter`（请求如何从 Coyote 进容器）

### 目标：理解“一条请求如何到 Servlet”

1. `Connector` 与 `ProtocolHandler`（如 `AbstractProtocol`、Http11NioProtocol）
2. `CoyoteAdapter.service()`
3. `StandardEngineValve` → `StandardHostValve` → `StandardContextValve` → `StandardWrapperValve`
4. `StandardWrapper`（loadServlet、allocate、service）

### 目标：理解“一个 Web 应用如何被加载”

1. `StandardContext`（start、部署、Loader、Resources）
2. `ContextConfig`（解析 web.xml、构造 Context）
3. `WebappLoader` / `WebappClassLoaderBase`（应用类加载器）

### 目标：理解 JSP 与 EL

1. `org.apache.jasper.servlet.JspServlet`
2. JSP 编译流程（如 `JspCompilationContext`、编译器）
3. `org.apache.el.*`（EL 解析与执行）

---

## 八、构建产物与运行目录（对照用）

- **Ant 默认输出**：`output/build/`（即运行时的 CATALINA_HOME）
- **主要 JAR**（便于对应源码包）：
  - `catalina.jar`：容器、启动、Valve、Realm 等
  - `tomcat-coyote.jar`：Coyote 协议层
  - `tomcat-util*.jar`：工具类
  - `jasper.jar`：JSP
  - `el-api.jar` / EL 实现等

按上述“入口 → server.xml → 请求链路 → 容器层级 → 模块”的顺序跳着读，再结合本文档的包和类名，会更容易在源码里定位和串联逻辑。
