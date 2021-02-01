/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.servlet;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.UnavailableException;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.codahale.metrics.jvm.ClassLoadingGaugeSet;
import com.codahale.metrics.jvm.GarbageCollectorMetricSet;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.codahale.metrics.jvm.ThreadStatesGaugeSet;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.tag.Tags;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.lucene.util.Version;
import org.apache.solr.api.AnnotatedApi;
import org.apache.solr.api.V2HttpCall;
import org.apache.solr.client.solrj.impl.XMLResponseParser;
import org.apache.solr.common.ParWork;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.ConnectionManager;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.CorePropertiesLocator;
import org.apache.solr.core.NodeConfig;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrInfoBean;
import org.apache.solr.core.SolrPaths;
import org.apache.solr.core.SolrXmlConfig;
import org.apache.solr.logging.MDCLoggingContext;
import org.apache.solr.metrics.AltBufferPoolMetricSet;
import org.apache.solr.metrics.MetricsMap;
import org.apache.solr.metrics.SolrMetricManager;
import org.apache.solr.metrics.SolrMetricProducer;
import org.apache.solr.rest.schema.FieldTypeXmlAdapter;
import org.apache.solr.security.AuditEvent;
import org.apache.solr.security.AuthenticationPlugin;
import org.apache.solr.security.PKIAuthenticationPlugin;
import org.apache.solr.security.PublicKeyHandler;
import org.apache.solr.util.StartupLoggingUtils;
import org.apache.solr.util.configuration.SSLConfigurationsFactory;
import org.apache.solr.util.tracing.GlobalTracer;
import org.apache.zookeeper.KeeperException;
import org.eclipse.jetty.client.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.security.AuditEvent.EventType;

/**
 * This filter looks at the incoming URL maps them to handlers defined in solrconfig.xml
 *
 * @since solr 1.2
 */
public class SolrDispatchFilter extends BaseSolrFilter {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  static {
    log.warn("expected pre init of factories {} {} {} {} {} {}",
        FieldTypeXmlAdapter.dbf, XMLResponseParser.inputFactory, XMLResponseParser.saxFactory,
        AnnotatedApi.MAPPER, org.apache.http.conn.util.PublicSuffixMatcherLoader.getDefault());
  }

  private volatile StopRunnable stopRunnable;
  private volatile Future<?> loadCoresFuture;

  private static class LiveThread extends Thread {
    @Override
    public void run() {
      try {
        Thread.sleep(Integer.MAX_VALUE);
      } catch (InterruptedException e) {
        // okay, stop
      }
    }
  }

  private final Thread liveThread = new LiveThread();
  protected volatile CoreContainer cores;
  protected final CountDownLatch init = new CountDownLatch(1);

  protected volatile String abortErrorMessage = null;

  protected volatile HttpClient httpClient;
  private volatile ArrayList<Pattern> excludePatterns;
  
  private final boolean isV2Enabled = !"true".equals(System.getProperty("disable.v2.api", "false"));

  private final String metricTag = SolrMetricProducer.getUniqueMetricTag(this, null);
  private volatile SolrMetricManager metricManager;
  private volatile String registryName;
  private volatile boolean closeOnDestroy = true;
  private volatile SolrZkClient zkClient;

  /**
   * Enum to define action that needs to be processed.
   * PASSTHROUGH: Pass through to another filter via webapp.
   * FORWARD: Forward rewritten URI (without path prefix and core/collection name) to another filter in the chain
   * RETURN: Returns the control, and no further specific processing is needed.
   *  This is generally when an error is set and returned.
   * RETRY:Retry the request. In cases when a core isn't found to work with, this is set.
   */
  public enum Action {
    PASSTHROUGH, FORWARD, RETURN, RETRY, ADMIN, REMOTEQUERY, PROCESS
  }
  
  public SolrDispatchFilter() {
    liveThread.start();
  }

  public static final String PROPERTIES_ATTRIBUTE = "solr.properties";

  public static final String SOLRHOME_ATTRIBUTE = "solr.solr.home";

  public static final String INIT_CALL = "solr.init.call";

  public static final String SOLR_INSTALL_DIR_ATTRIBUTE = "solr.install.dir";

  public static final String SOLR_DEFAULT_CONFDIR_ATTRIBUTE = "solr.default.confdir";

  public static final String SOLR_LOG_MUTECONSOLE = "solr.log.muteconsole";

  public static final String SOLR_LOG_LEVEL = "solr.log.level";

  static {
    SSLConfigurationsFactory.current().init(); // TODO: if we don't need SSL, skip ...
  }

  @Override
  public void init(FilterConfig config) throws ServletException {
    log.info("SolrDispatchFilter.init(): {}", this.getClass().getClassLoader());

    Properties extraProperties = (Properties) config.getServletContext().getAttribute(PROPERTIES_ATTRIBUTE);
    if (extraProperties == null) extraProperties = new Properties();

    Runnable initCall = (Runnable) config.getServletContext().getAttribute(INIT_CALL);
    if (initCall != null) {
      initCall.run();
    }

    if (extraProperties.size() > 0) {
      log.info("Using extra properties {}", extraProperties);
    }

    CoreContainer coresInit = null;
    try {

      StartupLoggingUtils.checkLogDir();

      log.info("Using logger factory {}", StartupLoggingUtils.getLoggerImplStr());

      logWelcomeBanner();
      String muteConsole = System.getProperty(SOLR_LOG_MUTECONSOLE);
      if (muteConsole != null && !Arrays.asList("false", "0", "off", "no").contains(muteConsole.toLowerCase(Locale.ROOT))) {
        StartupLoggingUtils.muteConsole();
      }
      String logLevel = System.getProperty(SOLR_LOG_LEVEL);
      if (logLevel != null) {
        log.info("Log level override, property solr.log.level={}", logLevel);
        StartupLoggingUtils.changeLogLevel(logLevel);
      }

      String exclude = config.getInitParameter("excludePatterns");
      if (exclude != null) {
        String[] excludeArray = exclude.split(",");
        excludePatterns = new ArrayList<>();
        for (String element : excludeArray) {
          excludePatterns.add(Pattern.compile(element));
        }
      }
      try {

        String solrHome = (String) config.getServletContext().getAttribute(SOLRHOME_ATTRIBUTE);
        final Path solrHomePath = solrHome == null ? SolrPaths.locateSolrHome() : Paths.get(solrHome);
        coresInit = createCoreContainer(solrHomePath, extraProperties);

        CoreContainer finalCoresInit = coresInit;
//        stopRunnable = new StopRunnable(coresInit);
//        SolrLifcycleListener.registerStopped(stopRunnable);

        SolrPaths.ensureUserFilesDataDir(solrHomePath);
        setupJvmMetrics(coresInit);
        if (log.isDebugEnabled()) {
          log.debug("user.dir={}", System.getProperty("user.dir"));
        }
        loadCoresFuture.get();
      } catch (Throwable t) {
        // catch this so our filter still works
        log.error("Could not start Solr. Check solr/home property and the logs");
        SolrCore.log(t);
        if (t instanceof Error) {
          throw (Error) t;
        }
      }
    } finally {
      if (cores != null) {
        this.httpClient = cores.getUpdateShardHandler().getTheSharedHttpClient().getHttpClient();
      }
      init.countDown();
      log.info("SolrDispatchFilter.init() end");
    }
  }

  private void stopCoreContainer(CoreContainer finalCoresInit) {

    IOUtils.closeQuietly(finalCoresInit);
    cores = null;
    httpClient = null;
    if (zkClient != null) {
      zkClient.disableCloseLock();
    }

    try (ParWork parWork = new ParWork(this, true, true)) {
      parWork.collect("", ()->{
        ParWork.close(zkClient);
      });
      parWork.collect("", ()->{
        liveThread.interrupt();
      });
    }
  }

  private void setupJvmMetrics(CoreContainer coresInit)  {
    metricManager = coresInit.getMetricManager();
    registryName = SolrMetricManager.getRegistryName(SolrInfoBean.Group.jvm);
    final Set<String> hiddenSysProps = coresInit.getConfig().getMetricsConfig().getHiddenSysProps();
    try {
      metricManager.registerAll(registryName, new AltBufferPoolMetricSet(), true, "buffers");
      metricManager.registerAll(registryName, new ClassLoadingGaugeSet(), true, "classes");
      // nocommit - this still appears fairly costly
      // metricManager.registerAll(registryName, new OperatingSystemMetricSet(), true, "os");
      metricManager.registerAll(registryName, new GarbageCollectorMetricSet(), true, "gc");
      metricManager.registerAll(registryName, new MemoryUsageGaugeSet(), true, "memory");
      metricManager.registerAll(registryName, new ThreadStatesGaugeSet(), true, "threads"); // todo should we use CachedThreadStatesGaugeSet instead?
      MetricsMap sysprops = new MetricsMap((detailed, map) -> {
        System.getProperties().forEach((k, v) -> {
          if (!hiddenSysProps.contains(k)) {
            map.put(String.valueOf(k), v);
          }
        });
      });
      metricManager.registerGauge(null, registryName, sysprops, metricTag, true, "properties", "system");
    } catch (Exception e) {
      ParWork.propagateInterrupt(e);
      log.warn("Error registering JVM metrics", e);
    }
  }

  private void logWelcomeBanner() {
    // _Really_ sorry about how clumsy this is as a result of the logging call checker, but this is the only one
    // that's so ugly so far.
    if (log.isInfoEnabled()) {
      log.info(" ___      _       Welcome to Apache Solr™ version {}", solrVersion());
    }
    if (log.isInfoEnabled()) {
      log.info("/ __| ___| |_ _   Starting in {} mode on port {}", isCloudMode() ? "cloud" : "standalone", getSolrPort());
    }
    if (log.isInfoEnabled()) {
      log.info("\\__ \\/ _ \\ | '_|  Install dir: {}", System.getProperty(SOLR_INSTALL_DIR_ATTRIBUTE));
    }
    if (log.isInfoEnabled()) {
      log.info("|___/\\___/_|_|    Start time: {}", Instant.now());
    }
  }

  private String solrVersion() {
    String specVer = Version.LATEST.toString();
    try {
      String implVer = SolrCore.class.getPackage().getImplementationVersion();
      if (implVer == null) {
        return specVer;
      }
      return (specVer.equals(implVer.split(" ")[0])) ? specVer : implVer;
    } catch (Exception e) {
      return specVer;
    }
  }

  private String getSolrPort() {
    return System.getProperty("jetty.port");
  }

  /* We are in cloud mode if Java option zkRun exists OR zkHost exists and is non-empty */
  private boolean isCloudMode() {
    return ((System.getProperty("zkHost") != null && !StringUtils.isEmpty(System.getProperty("zkHost")))
    || System.getProperty("zkRun") != null);
  }

  /**
   * Override this to change CoreContainer initialization
   * @return a CoreContainer to hold this server's cores
   */
  protected synchronized CoreContainer createCoreContainer(Path solrHome, Properties extraProperties) throws IOException {
    String zkHost = System.getProperty("zkHost");
    if (!StringUtils.isEmpty(zkHost)) {
      int zkClientTimeout = Integer.getInteger("zkClientTimeout", 45000); // nocommit - must come from zk settings, we should parse more here and set this up vs waiting for zkController
      if (zkClient != null) {
        throw new IllegalStateException();
      }
      zkClient = new SolrZkClient(zkHost, zkClientTimeout);
      zkClient.enableCloseLock();
      zkClient.start();
    }

    NodeConfig nodeConfig = loadNodeConfig(zkClient, solrHome, extraProperties);
    this.cores = new CoreContainer(zkClient, nodeConfig,  new CorePropertiesLocator(nodeConfig.getCoreRootDirectory()), true);
    if (zkClient != null) zkClient.setHigherLevelIsClosed(new ConnectionManager.IsClosed() {
      @Override
      public boolean isClosed() {
        return cores.isShutDown();
      }
    });
    loadCoresFuture = ParWork.getRootSharedExecutor().submit(() -> cores.load());
    return cores;
  }

  /**
   * Get the NodeConfig whether stored on disk, in ZooKeeper, etc.
   * This may also be used by custom filters to load relevant configuration.
   * @return the NodeConfig
   */
  public static NodeConfig loadNodeConfig(SolrZkClient zkClient, Path solrHome, Properties nodeProperties) throws IOException {
    if (!StringUtils.isEmpty(System.getProperty("solr.solrxml.location"))) {
      log.warn("Solr property solr.solrxml.location is no longer supported. Will automatically load solr.xml from ZooKeeper if it exists");
    }

    if (zkClient != null) {
      try {

        log.info("Trying solr.xml in ZooKeeper...");

        byte[] data = zkClient.getData("/solr.xml", null, null);
        if (data == null) {
          log.error("Found solr.xml in ZooKeeper with no data in it");
          throw new SolrException(ErrorCode.SERVER_ERROR, "Found solr.xml in ZooKeeper with no data in it");
        }
        return new SolrXmlConfig().fromInputStream(solrHome, new ByteArrayInputStream(data), nodeProperties);
      } catch (KeeperException.NoNodeException e) {
        // okay
      } catch (Exception e) {
        SolrZkClient.checkInterrupted(e);
        throw new SolrException(ErrorCode.SERVER_ERROR, "Error occurred while loading solr.xml from zookeeper", e);
      }
    }
    log.info("Loading solr.xml from SolrHome (not found in ZooKeeper)");

    return new SolrXmlConfig().fromSolrHome(solrHome, nodeProperties);
  }
  
  public CoreContainer getCores() {
    return cores;
  }
  
  @Override
  public void destroy() {
    if (cores != null && cores.isZooKeeperAware())  {
      MDCLoggingContext.setNode(cores.getZkController().getNodeName());
    }
    try {
      close();
    } finally {
      MDCLoggingContext.clear();
    }
  }
  
  public void close() {
    CoreContainer cc = cores;

    try {
//      if (metricManager != null) {
//        try {
//          metricManager.unregisterGauges(registryName, metricTag);
//        } catch (NullPointerException e) {
//          // okay
//        } catch (Exception e) {
//          log.warn("Exception closing FileCleaningTracker", e);
//        } finally {
//          metricManager = null;
//        }
//      }
    } finally {
     // if (!cc.isShutDown()) {
        log.info("CoreContainer is not yet shutdown during filter destroy, shutting down now {}", cc);
        GlobalTracer.get().close();
        stopCoreContainer(cc);
    //  }



//      if (SolrLifcycleListener.isRegisteredStopped(stopRunnable)) {
//        SolrLifcycleListener.removeStopped(stopRunnable);
//      }
    }
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
    doFilter(request, response, chain, false);
  }
  
  public void doFilter(ServletRequest _request, ServletResponse _response, FilterChain chain, boolean retry) throws IOException, ServletException {
    if (!(_request instanceof HttpServletRequest)) return;
    HttpServletRequest request = closeShield((HttpServletRequest)_request, retry);
    HttpServletResponse response = closeShield((HttpServletResponse)_response, retry);
    Scope scope = null;
    Span span = null;
    try {

      if (cores == null || cores.isShutDown()) {
        try {
          init.await();
        } catch (InterruptedException e) { //well, no wait then
          ParWork.propagateInterrupt(e);
        }
        final String msg = "Error processing the request. CoreContainer is either not initialized or shutting down.";
        if (cores == null || cores.isShutDown()) {
          log.error(msg);
          throw new UnavailableException(msg);
        }
      }

      String requestPath = ServletUtils.getPathAfterContext(request);
      // No need to even create the HttpSolrCall object if this path is excluded.
      if (excludePatterns != null) {
        for (Pattern p : excludePatterns) {
          Matcher matcher = p.matcher(requestPath);
          if (matcher.lookingAt()) {
            chain.doFilter(request, response);
            return;
          }
        }
      }

      SpanContext parentSpan = GlobalTracer.get().extract(request);
      Tracer tracer = GlobalTracer.getTracer();

      Tracer.SpanBuilder spanBuilder = null;
      String hostAndPort = request.getServerName() + "_" + request.getServerPort();
      if (parentSpan == null) {
        spanBuilder = tracer.buildSpan(request.getMethod() + ":" + hostAndPort);
      } else {
        spanBuilder = tracer.buildSpan(request.getMethod() + ":" + hostAndPort)
            .asChildOf(parentSpan);
      }

      spanBuilder
          .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
          .withTag(Tags.HTTP_URL.getKey(), request.getRequestURL().toString());
      span = spanBuilder.start();
      scope = tracer.scopeManager().activate(span);

      AtomicReference<HttpServletRequest> wrappedRequest = new AtomicReference<>();
      if (!authenticateRequest(request, response, wrappedRequest)) { // the response and status code have already been sent
        return;
      }
      if (wrappedRequest.get() != null) {
        request = wrappedRequest.get();
      }

      if (cores.getAuthenticationPlugin() != null) {
        if (log.isDebugEnabled()) {
          log.debug("User principal: {}", request.getUserPrincipal());
        }
      }

      HttpSolrCall call = getHttpSolrCall(request, response, retry);
      ExecutorUtil.setServerThreadFlag(Boolean.TRUE);
      try {
        Action result = call.call();
        if (log.isDebugEnabled()) log.debug("Call type is {}", result);
        switch (result) {
          case PASSTHROUGH:
            chain.doFilter(request, response);
            break;
          case RETRY:
            doFilter(request, response, chain, true); // RECURSION
            break;
          case FORWARD:
            request.getRequestDispatcher(call.getPath()).forward(request, response);
            break;
          case ADMIN:
          case PROCESS:
          case REMOTEQUERY:
          case RETURN:
            break;
        }
      } finally {
        call.destroy();
        ExecutorUtil.setServerThreadFlag(null);
      }
    } catch(Exception e) {
      log.error("Solr ran into an unexpected problem.", e);
      int code = 500;
      if (e instanceof SolrException) {
        code = ((SolrException) e).code();
      }
      response.sendError(code, e.getClass().getName() + " " + e.getMessage());
    } finally {
      if (span != null) span.finish();
      if (scope != null) scope.close();

      consumeInputFully(request, response);
      GlobalTracer.get().clearContext();
      SolrRequestParsers.cleanupMultipartFiles(request);
    }
  }
  
  // we make sure we read the full client request so that the client does
  // not hit a connection reset and we can reuse the 
  // connection - see SOLR-8453 and SOLR-8683
  private void consumeInputFully(HttpServletRequest req, HttpServletResponse response) {
    try {
      ServletInputStream is = req.getInputStream();
      while (!is.isFinished() && is.read() != -1) {}
    } catch (IOException e) {
      if (req.getHeader(HttpHeaders.EXPECT) != null && response.isCommitted()) {
        log.debug("No input stream to consume from client");
      } else {
        log.info("Could not consume full client request", e);
      }
    }
  }
  
  /**
   * Allow a subclass to modify the HttpSolrCall.  In particular, subclasses may
   * want to add attributes to the request and send errors differently
   */
  protected HttpSolrCall getHttpSolrCall(HttpServletRequest request, HttpServletResponse response, boolean retry) {
    String path = ServletUtils.getPathAfterContext(request);

    if (isV2Enabled && (path.startsWith("/____v2/") || path.equals("/____v2"))) {
      if (log.isDebugEnabled()) log.debug("V2 http call");
      return new V2HttpCall(this, cores, request, response, false);
    } else {
      if (log.isDebugEnabled()) log.debug("V1 http call");
      return new HttpSolrCall(this, cores, request, response, retry);
    }
  }

  private boolean authenticateRequest(HttpServletRequest request, HttpServletResponse response, final AtomicReference<HttpServletRequest> wrappedRequest) throws IOException {
    boolean requestContinues = false;
    final AtomicBoolean isAuthenticated = new AtomicBoolean(false);
    AuthenticationPlugin authenticationPlugin = cores.getAuthenticationPlugin();
    if (authenticationPlugin == null) {
      if (shouldAudit(EventType.ANONYMOUS)) {
        cores.getAuditLoggerPlugin().doAudit(new AuditEvent(EventType.ANONYMOUS, request));
      }
      return true;
    } else {
      // /admin/info/key must be always open. see SOLR-9188
      String requestPath = ServletUtils.getPathAfterContext(request);
      if (PublicKeyHandler.PATH.equals(requestPath)) {
        log.debug("Pass through PKI authentication endpoint");
        return true;
      }
      // /solr/ (Admin UI) must be always open to allow displaying Admin UI with login page  
      if ("/solr/".equals(requestPath) || "/".equals(requestPath)) {
        log.debug("Pass through Admin UI entry point");
        return true;
      }
      String header = request.getHeader(PKIAuthenticationPlugin.HEADER);
      if (header != null && cores.getPkiAuthenticationPlugin() != null)
        authenticationPlugin = cores.getPkiAuthenticationPlugin();
      try {
        if (log.isDebugEnabled()) {
          log.debug("Request to authenticate: {}, domain: {}, port: {}", request, request.getLocalName(), request.getLocalPort());
        }
        // upon successful authentication, this should call the chain's next filter.
        requestContinues = authenticationPlugin.authenticate(request, response, (req, rsp) -> {
          isAuthenticated.set(true);
          wrappedRequest.set((HttpServletRequest) req);
        });
      } catch (Exception e) {
        log.info("Error authenticating", e);
        throw new SolrException(ErrorCode.SERVER_ERROR, "Error during request authentication, ", e);
      }
    }
    // requestContinues is an optional short circuit, thus we still need to check isAuthenticated.
    // This is because the AuthenticationPlugin doesn't always have enough information to determine if
    // it should short circuit, e.g. the Kerberos Authentication Filter will send an error and not
    // call later filters in chain, but doesn't throw an exception.  We could force each Plugin
    // to implement isAuthenticated to simplify the check here, but that just moves the complexity to
    // multiple code paths.
    if (!requestContinues || !isAuthenticated.get()) {
      if (shouldAudit(EventType.REJECTED)) {
        cores.getAuditLoggerPlugin().doAudit(new AuditEvent(EventType.REJECTED, request));
      }
      return false;
    }
    if (shouldAudit(EventType.AUTHENTICATED)) {
      cores.getAuditLoggerPlugin().doAudit(new AuditEvent(EventType.AUTHENTICATED, request));
    }
    return true;
  }
  
  public static class ClosedServletInputStream extends ServletInputStream {
    
    public static final ClosedServletInputStream CLOSED_SERVLET_INPUT_STREAM = new ClosedServletInputStream();

    @Override
    public int read() {
      return -1;
    }

    @Override
    public boolean isFinished() {
      return false;
    }

    @Override
    public boolean isReady() {
      return false;
    }

    @Override
    public void setReadListener(ReadListener arg0) {}
  }
  
  public static class ClosedServletOutputStream extends ServletOutputStream {
    
    public static final ClosedServletOutputStream CLOSED_SERVLET_OUTPUT_STREAM = new ClosedServletOutputStream();
    
    @Override
    public void write(final int b) throws IOException {
      throw new IOException("write(" + b + ") failed: stream is closed");
    }
    
    @Override
    public void flush() throws IOException {
      throw new IOException("flush() failed: stream is closed");
    }

    @Override
    public boolean isReady() {
      return false;
    }

    @Override
    public void setWriteListener(WriteListener arg0) {
      throw new RuntimeException("setWriteListener() failed: stream is closed");
    }
  }

  private static String CLOSE_STREAM_MSG = "Attempted close of http request or response stream - in general you should not do this, "
      + "you may spoil connection reuse and possibly disrupt a client. If you must close without actually needing to close, "
      + "use a CloseShield*Stream. Closing or flushing the response stream commits the response and prevents us from modifying it. "
      + "Closing the request stream prevents us from gauranteeing ourselves that streams are fully read for proper connection reuse."
      + "Let the container manage the lifecycle of these streams when possible.";
 

  /**
   * Check if audit logging is enabled and should happen for given event type
   * @param eventType the audit event
   */
  private boolean shouldAudit(AuditEvent.EventType eventType) {
    return cores.getAuditLoggerPlugin() != null && cores.getAuditLoggerPlugin().shouldLog(eventType);
  }
  
  /**
   * Wrap the request's input stream with a close shield. If this is a
   * retry, we will assume that the stream has already been wrapped and do nothing.
   *
   * Only the container should ever actually close the servlet output stream.
   *
   * @param request The request to wrap.
   * @param retry If this is an original request or a retry.
   * @return A request object with an {@link InputStream} that will ignore calls to close.
   */
  public static HttpServletRequest closeShield(HttpServletRequest request, boolean retry) {
    if (!retry) {
      return new HttpServletRequestWrapper(request) {
        @Override
        public ServletInputStream getInputStream() throws IOException {
          return new CloseShieldServletInputStreamWrapper(request.getInputStream());
        }
      };
    } else {
      return request;
    }
  }
  
  /**
   * Wrap the response's output stream with a close shield. If this is a
   * retry, we will assume that the stream has already been wrapped and do nothing.
   *
   * Only the container should ever actually close the servlet request stream.
   *
   * @param response The response to wrap.
   * @param retry If this response corresponds to an original request or a retry.
   * @return A response object with an {@link OutputStream} that will ignore calls to close.
   */
  public static HttpServletResponse closeShield(HttpServletResponse response, boolean retry) {
    if (!retry) {
      return new HttpServletResponseWrapper(response) {
        @Override
        public ServletOutputStream getOutputStream() throws IOException {
          return new CloseShieldServletOutputStreamWrapper(response.getOutputStream());
        }

        @Override
        public void flushBuffer() throws IOException {
          // no flush, commits response and messes up chunked encoding stuff
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
          if (sc != 404) {
            log.error(sc + ":" + msg);
          }
          response.setStatus(sc);
          PrintWriter writer = new PrintWriter(getOutputStream());
          writer.write(msg);
        }

        @Override
        public void sendError(int sc) throws IOException {
          sendError(sc, "Solr ran into an unexpected problem and doesn't seem to know more about it. There may be more information in the Solr logs. code=" + sc);
        }
      };
    } else {
      return response;
    }
  }

  private static class CloseShieldServletInputStreamWrapper extends ServletInputStreamWrapper {
    public CloseShieldServletInputStreamWrapper(ServletInputStream stream) throws IOException {
      super(stream);
    }

    @Override
    public void close() {
      // don't allow close
    }
  }

  private class StopRunnable implements Runnable{

    private final CoreContainer coreContainer;

    public StopRunnable(CoreContainer coreContainer) {
      this.coreContainer = coreContainer;
    }

    public void run() {
      stopCoreContainer(coreContainer);
    }

  }

  private static class CloseShieldServletOutputStreamWrapper extends ServletOutputStreamWrapper {
    public CloseShieldServletOutputStreamWrapper(ServletOutputStream stream) {
      super(stream);
    }

    @Override
    public void close() {
      // don't allow close
    }
  }
}
