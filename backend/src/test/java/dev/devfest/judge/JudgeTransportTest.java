package dev.devfest.judge;
import com.sun.net.httpserver.HttpServer;
import java.net.*;import java.net.http.*;import java.time.Duration;import java.util.concurrent.*;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class JudgeTransportTest {
 @Test void oversizedBodyIsRejected() throws Exception {
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",x->{try{x.sendResponseHeaders(200,1048577);x.getResponseBody().write(new byte[1048577]);}finally{x.close();}});server.start();
  try {var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/")).timeout(Duration.ofSeconds(3)).build();assertThrows(Exception.class,()->Judge0Client.boundedResponse(HttpClient.newHttpClient(),request));}finally{server.stop(0);}
 }
 @Test void stalledBodyCannotHoldWorker() throws Exception {
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var release=new CountDownLatch(1);
  server.createContext("/",x->{try{x.sendResponseHeaders(200,100);x.getResponseBody().write(1);x.getResponseBody().flush();release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{x.close();}});server.start();
  try {var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/")).timeout(Duration.ofMillis(300)).build();long start=System.nanoTime();assertThrows(Exception.class,()->Judge0Client.boundedResponse(HttpClient.newHttpClient(),request));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2000);}finally{release.countDown();server.stop(0);}
 }
}
