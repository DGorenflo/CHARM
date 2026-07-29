package de.uni_mannheim.informatik.dws.melt.receiver_http;

import de.uni_mannheim.informatik.dws.melt.matching_base.receiver.MainMatcherClassExtractor;
import de.uni_mannheim.informatik.dws.melt.matching_base.typetransformer.AlignmentAndParameters;
import de.uni_mannheim.informatik.dws.melt.matching_base.typetransformer.GenericMatcherCaller;
import de.uni_mannheim.informatik.dws.melt.matching_base.typetransformer.TypeTransformerRegistry;
import de.uni_mannheim.informatik.dws.melt.matching_jena.OntologyCacheJena;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.QoSFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    private static final File LOCATION = getLocation();
    private static final SecureRandom random = new SecureRandom();

    // job state: null = running, File = done, Exception = failed
    private static final Map<String, Object> jobs = new ConcurrentHashMap<>();
    // job token data: jobId -> CSV rows (available once job completes)
    private static final Map<String, List<String>> jobTokens = new ConcurrentHashMap<>();
    // single-threaded: only one matcher job runs at a time to bound peak heap usage
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void main(String[] args) throws Exception {
        try (var is = Main.class.getClassLoader().getResourceAsStream("logging.properties")) {
            if (is != null) java.util.logging.LogManager.getLogManager().readConfiguration(is);
        }
        System.setOut(new java.io.PrintStream(System.out, true));
        System.setErr(new java.io.PrintStream(System.err, true));
        int port = getPort();

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setRequestHeaderSize(64 * 1024);

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        connector.setHost("0.0.0.0");
        connector.setPort(port);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        // POST /match  — submit job, returns {"jobId":"..."}
        ServletHolder matchHolder = context.addServlet(MatchServlet.class, "/match");
        matchHolder.getRegistration().setMultipartConfig(new MultipartConfigElement(LOCATION.getAbsolutePath(), -1, -1, 1));

        // GET /result/{jobId} — poll for result
        context.addServlet(ResultServlet.class, "/result/*");

        // GET /tokens/{jobId} — fetch token usage CSV rows after job completes
        context.addServlet(TokensServlet.class, "/tokens/*");

        LOGGER.info("Matching service runs at: http://localhost:{}/match", port);
        server.start();
        server.join();
    }

    // --- POST /match ---
    public static class MatchServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            if (!isMultipartContent(request)) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Multipart request required");
                return;
            }

            Part sourcePart = request.getPart("source");
            Part targetPart = request.getPart("target");
            if (sourcePart == null || targetPart == null) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing source or target");
                return;
            }

            File sourceFile = getFile(sourcePart, "source");
            File targetFile = getFile(targetPart, "target");
            if (sourceFile.length() == 0 || targetFile.length() == 0) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Source or target file is empty");
                return;
            }

            Part inputAlignmentPart = request.getPart("inputAlignment");
            Part parametersPart = request.getPart("parameters");
            File inputAlignmentFile = getFile(inputAlignmentPart, "inputAlignment");
            File parametersFile = getFile(parametersPart, "parameters");

            String jobId = UUID.randomUUID().toString();
            jobs.put(jobId, "RUNNING");

            executor.submit(() -> {
                charm.bootstrap.semantics.pipeline.LlmDecider.resetRegistry();
                try {
                    URL inputAlignmentURL = inputAlignmentFile != null ? inputAlignmentFile.toURI().toURL() : null;
                    URL parametersURL = parametersFile != null ? parametersFile.toURI().toURL() : null;
                    URL resultURL = runTool(sourceFile.toURI().toURL(), targetFile.toURI().toURL(), inputAlignmentURL, parametersURL);
                    charm.bootstrap.semantics.pipeline.LlmDecider.logSummary();
                    jobTokens.put(jobId, charm.bootstrap.semantics.pipeline.LlmDecider.getCsvRows());
                    jobs.put(jobId, resultURL != null ? resultURL : "NO_RESULT");
                } catch (Exception ex) {
                    charm.bootstrap.semantics.pipeline.LlmDecider.logSummary();
                    jobTokens.put(jobId, charm.bootstrap.semantics.pipeline.LlmDecider.getCsvRows());
                    LOGGER.error("Job {} failed", jobId, ex);
                    jobs.put(jobId, ex);
                } finally {
                    OntologyCacheJena.emptyCache();
                    sourceFile.delete();
                    targetFile.delete();
                    if (inputAlignmentFile != null) inputAlignmentFile.delete();
                    if (parametersFile != null) parametersFile.delete();
                }
            });

            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
            response.getWriter().write("{\"jobId\":\"" + jobId + "\"}");
            LOGGER.info("Job {} submitted", jobId);
        }
    }

    // --- GET /result/{jobId} ---
    public static class ResultServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String path = request.getPathInfo();
            if (path == null || path.length() < 2) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing jobId");
                return;
            }
            String jobId = path.substring(1);
            Object state = jobs.get(jobId);

            if (state == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown jobId");
            } else if ("RUNNING".equals(state)) {
                response.setContentType("application/json");
                response.setStatus(HttpServletResponse.SC_ACCEPTED);
                response.getWriter().write("{\"status\":\"running\"}");
            } else if (state instanceof Exception) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ((Exception) state).getMessage());
                jobs.remove(jobId);
            } else if ("NO_RESULT".equals(state)) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Matcher returned no result");
                jobs.remove(jobId);
            } else {
                jobs.remove(jobId);
                sendFileContent((URL) state, response);
            }
        }
    }

    // --- GET /tokens/{jobId} — returns CSV rows for token usage ---
    public static class TokensServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String path = request.getPathInfo();
            if (path == null || path.length() < 2) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing jobId");
                return;
            }
            String jobId = path.substring(1);
            List<String> rows = jobTokens.remove(jobId);
            if (rows == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "No token data for jobId (job still running or unknown)");
                return;
            }
            response.setContentType("text/csv");
            response.setStatus(HttpServletResponse.SC_OK);
            for (String row : rows) {
                response.getWriter().println(row);
            }
        }
    }

    private static void sendFileContent(URL url, HttpServletResponse response) {
        File mappingFile;
        try {
            mappingFile = new File(url.toURI());
        } catch (URISyntaxException ex) {
            LOGGER.error("Couldn't convert result URL to URI");
            return;
        }
        if (!mappingFile.exists()) {
            LOGGER.error("Mapping file does not exist");
            return;
        }
        response.setContentType("text/plain");
        response.setContentLength((int) mappingFile.length());
        response.setHeader("Content-Disposition", String.format("attachment; filename=\"%s\"", mappingFile.getName()));
        byte[] buffer = new byte[4096];
        try (InputStream inStream = new FileInputStream(mappingFile);
             OutputStream outStream = response.getOutputStream()) {
            int bytesRead;
            while ((bytesRead = inStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, bytesRead);
            }
        } catch (IOException ex) {
            LOGGER.error("Could not send the mapping file.", ex);
        } finally {
            mappingFile.delete();
        }
    }

    private static URL runTool(URL source, URL target, URL inputAlignment, URL parameters) throws Exception {
        String mainClass = MainMatcherClassExtractor.extractMainClass();
        LOGGER.info("Starting matcher class {} — source:{} target:{}", mainClass, source, target);
        AlignmentAndParameters result = GenericMatcherCaller.runMatcher(mainClass, source, target, inputAlignment, parameters);
        if (result.getAlignment() == null)
            throw new Exception("Matcher returned null alignment");
        return TypeTransformerRegistry.getTransformedObject(result.getAlignment(), URL.class);
    }

    private static FilterHolder getQoSFilter(int maxRequests) {
        FilterHolder holder = new FilterHolder(new QoSFilter());
        holder.setInitParameter("maxRequests", String.valueOf(maxRequests));
        return holder;
    }

    private static boolean isMultipartContent(HttpServletRequest request) {
        if (!"post".equals(request.getMethod().toLowerCase())) return false;
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase().startsWith("multipart/");
    }

    private static String getHtmlCode() {
        try (InputStream inputStream = Main.class.getClassLoader().getResourceAsStream("static/index.html")) {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int length; (length = inputStream.read(buffer)) != -1; )
                result.write(buffer, 0, length);
            return result.toString(StandardCharsets.UTF_8.name());
        } catch (IOException ex) {
            LOGGER.warn("Could not load index.html");
            return "";
        }
    }

    private static File getFile(Part part, String prefix) throws IOException {
        if (part == null) return null;
        long n = random.nextLong();
        n = (n == Long.MIN_VALUE) ? 0 : Math.abs(n);
        String filename = prefix + "-" + n + "." + getFilenameExtension(part.getSubmittedFileName(), "rdf");
        part.write(filename);
        return new File(LOCATION, filename);
    }

    private static String getFilenameExtension(String filename, String defaultValue) {
        if (filename == null) return defaultValue;
        int index = filename.lastIndexOf('.');
        return index == -1 ? defaultValue : filename.substring(index + 1);
    }

    private static int getPort() {
        int port = 8080;
        String envPort = System.getenv("MELT_PORT");
        if (envPort != null) {
            try { port = Integer.parseInt(envPort); }
            catch (NumberFormatException e) { LOGGER.warn("could not parse MELT_PORT - using 8080"); }
        }
        return port;
    }

    private static int getMaxParallelRequests() {
        int maxRequests = 1;
        String max = System.getenv("MELT_MAX_REQUESTS");
        if (max != null) {
            try { maxRequests = Integer.parseInt(max); }
            catch (NumberFormatException e) { LOGGER.warn("could not parse MELT_MAX_REQUESTS - using 1"); }
        }
        return maxRequests;
    }

    private static File getLocation() {
        String location = System.getenv("MELT_LOCATION");
        if (location == null) return getDefaultLocation();
        File locationDir = new File(location);
        if (!locationDir.isDirectory()) {
            LOGGER.warn("MELT_LOCATION is not a directory - using default");
            return getDefaultLocation();
        }
        locationDir.mkdirs();
        return locationDir;
    }

    private static File getDefaultLocation() {
        try {
            File f = Files.createTempDirectory("MELT-fileupload").toFile();
            f.mkdirs();
            return f;
        } catch (IOException ex) {
            LOGGER.error("Could not create tmp directory", ex);
            return null;
        }
    }
}
