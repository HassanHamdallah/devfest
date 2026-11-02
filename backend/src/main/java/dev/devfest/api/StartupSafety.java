package dev.devfest.api;
import dev.devfest.event.service.AiRoomShuffleSecret;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.env.Environment;
/** Checks configuration before Flyway or any other database client is constructed. */
@Configuration
public class StartupSafety {
 @Bean static BeanFactoryPostProcessor validateDestination(Environment env) {
  return beanFactory -> {
   String database=env.getRequiredProperty("spring.datasource.url");
   if(!database.matches("jdbc:postgresql://[^/]+/devfest(_test)?([?].*)?"))
    throw new IllegalStateException("DevFest refuses a database other than devfest or devfest_test");
   String judge=env.getProperty("app.judge-url","");
   boolean development=env.getProperty("app.development",Boolean.class,false);
   if(!development&&!AiRoomShuffleSecret.isSet(env.getProperty("app.quiz-shuffle-secret","")))
    throw new IllegalStateException(AiRoomShuffleSecret.MISSING);
   if(!judge.isBlank()&&!development){
    if("ce.judge0.com".equalsIgnoreCase(URI.create(judge).getHost()))
     throw new IllegalStateException("The shared public Judge0 endpoint is restricted to explicit development mode");
    if(!env.getProperty("app.judge-security-reviewed",Boolean.class,false))
     throw new IllegalStateException("Confirm Judge0 patching and isolation before enabling non-development execution");
   }
  };
 }
}
