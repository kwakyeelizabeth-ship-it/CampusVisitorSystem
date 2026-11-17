
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class Server {
    public static void main(String[] args) throws IOException {
        int port = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        
        server.createContext("/", new HomeHandler());
        server.createContext("/register", new RegisterHandler());
        
        server.setExecutor(null);
        server.start();
        
        System.out.println("==========================================");
        System.out.println("Backend Server running at: http://localhost:8080");
        System.out.println("Frontend files loaded from /public directory");
        System.out.println("==========================================");
    }

    // Serves the public/index.html frontend page
    static class HomeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            File file = new File("public/index.html");
            if (!file.exists()) {
                String error = "404 - Frontend index.html not found in public folder.";
                exchange.sendResponseHeaders(404, error.length());
                exchange.getResponseBody().write(error.getBytes());
                exchange.getResponseBody().close();
                return;
            }

            byte[] htmlBytes = Files.readAllBytes(file.toPath());
            exchange.sendResponseHeaders(200, htmlBytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(htmlBytes);
            os.close();
        }
    }

    // Processes form submission, saves data, and renders public/success.html
    static class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> params = parseQuery(body);

                String fullName = params.getOrDefault("fullName", "").trim();
                String phone = params.getOrDefault("phone", "").trim();
                String purpose = params.get("purpose");
                String hostName = params.get("hostName");

                if (!fullName.matches("[\\p{L}]+(?: +[\\p{L}]+)*")) {
                    sendValidationError(exchange, "Name must contain only letters and spaces.");
                    return;
                }
                String phoneDigits = phone.replaceAll("[^0-9]", "");
                if (!phone.matches("\\+?[0-9](?:[0-9 ()-]*[0-9])?")
                        || phoneDigits.length() < 7 || phoneDigits.length() > 15) {
                    sendValidationError(exchange, "Phone number must contain 7 to 15 digits; spaces, hyphens, parentheses, and a leading + are allowed.");
                    return;
                }

                // Business logic: Generate ID and save via file manager
                String visitorId = VisitorFileManager.generateVisitorId();
                Visitor newVisitor = new Visitor(visitorId, fullName, phone, purpose, hostName);
                VisitorFileManager.saveVisitor(newVisitor);

                // Read success.html template and inject dynamic data
                File file = new File("public/success.html");
                String htmlContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                
                htmlContent = htmlContent.replace("[[VISITOR_ID]]", visitorId);
                htmlContent = htmlContent.replace("[[FULL_NAME]]", fullName);

                byte[] responseBytes = htmlContent.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, responseBytes.length);
                OutputStream os = exchange.getResponseBody();
                os.write(responseBytes);
                os.close();
            }
        }

        private void sendValidationError(HttpExchange exchange, String message) throws IOException {
            byte[] response = message.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        private Map<String, String> parseQuery(String query) {
            Map<String, String> map = new HashMap<>();
            if (query == null) return map;
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length > 1) {
                    map.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                            URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                } else {
                    map.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), "");
                }
            }
            return map;
        }
    }
}