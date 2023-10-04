package org.vsnitko;

import static java.lang.String.format;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author v.snitko
 * @since 2023.10.02
 */
public class ProxyHandler implements HttpHandler {

    public static final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build();
    private static final Logger log = LoggerFactory.getLogger(ProxyHandler.class);

    @Override
    public void handle(HttpExchange clientExchange) {
        URI uri = null;
        try {
            final Headers requestHeaders = clientExchange.getRequestHeaders();

            final String host = Optional.ofNullable(requestHeaders.get("host"))
                .orElseThrow(() -> new NullPointerException(
                    "'host' header must not be empty. " +
                    "Seems like you called proxy server directly, but it must be handled by nginx"
                )).get(0);
            final String path = Optional.ofNullable(requestHeaders.get("x-original-uri")).orElse(List.of("")).get(0);
            uri = URI.create(format("http://%s%s", host, path));

            checkForSelfInvocation(uri);

            final byte[] response = sendRequestToOriginalServer(clientExchange, uri);

            sendResponseToClient(200, response, clientExchange);
            log.info("Success: {} {}", clientExchange.getRequestMethod(), uri);
        } catch (Exception e) {
            log.error(format("Exception while %s %s: ", clientExchange.getRequestMethod(), uri), e);
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            sendResponseToClient(500, sw.toString().getBytes(), clientExchange);
        }
    }

    private void checkForSelfInvocation(final URI uri) throws SocketException, UnknownHostException {
        if (NetworkInterface.getByInetAddress(InetAddress.getByName(uri.getHost())) != null) {
            throw new IllegalArgumentException(
                "Requests to proxy server must be handled by nginx, do not call it directly");
        }
    }

    private byte[] sendRequestToOriginalServer(
        final HttpExchange clientExchange,
        final URI url
    ) throws IOException, InterruptedException {
        final HttpRequest.Builder builder = HttpRequest.newBuilder().uri(url);

        // set headers
        clientExchange.getRequestHeaders().forEach(
            (key, value) -> {
                if (!List.of("Host", "Content-length", "Connection").contains(key)) { // restricted headers
                    builder.setHeader(key, value.get(0));
                }
            }
        );

        // set body
        final HttpRequest.BodyPublisher bodyPublisher =
            clientExchange.getRequestBody().available() > 0
            ? HttpRequest.BodyPublishers.ofInputStream(clientExchange::getRequestBody)
            : HttpRequest.BodyPublishers.noBody();
        final HttpRequest httpRequest = builder
            .method(clientExchange.getRequestMethod(), bodyPublisher)
            .build();

        // send request
        final HttpResponse<InputStream> httpResponse =
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        final InputStream responseBody = httpResponse.body();
        final Optional<String> encoding = httpResponse.headers().firstValue("content-encoding");
        if (encoding.isEmpty()) {
            return responseBody.readAllBytes();
        } else if (encoding.get().equals("gzip")) {
            return new GZIPInputStream(responseBody).readAllBytes();
        } else if (encoding.get().equals("deflate")) {
            return new InflaterInputStream(responseBody).readAllBytes();
        }
        return responseBody.readAllBytes();
    }

    private void sendResponseToClient(
        final int responseCode,
        final byte[] response,
        final HttpExchange clientExchange
    ) {
        try (OutputStream os = clientExchange.getResponseBody()) {
            clientExchange.sendResponseHeaders(responseCode, 0);
            os.write(response);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
