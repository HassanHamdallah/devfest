package dev.devfest.api;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.junit.jupiter.api.Assertions.*;
class StartupSafetyTest {
 private MockEnvironment base(){return new MockEnvironment().withProperty("spring.datasource.url","jdbc:postgresql://127.0.0.1:55432/devfest").withProperty("app.quiz-shuffle-secret","test-only-quiz-shuffle-secret-with-40-chars");}
 private void validate(MockEnvironment e){StartupSafety.validateDestination(e).postProcessBeanFactory(new DefaultListableBeanFactory());}
 @Test void rejectsOtherDatabaseBeforeBeansAreConstructed(){var e=base().withProperty("spring.datasource.url","jdbc:postgresql://localhost:5432/teamqora");assertThrows(IllegalStateException.class,()->validate(e));}
 @Test void permitsDedicatedTestDatabase(){assertDoesNotThrow(()->validate(base().withProperty("spring.datasource.url","jdbc:postgresql://127.0.0.1:55432/devfest_test")));}
 @Test void publicProviderRequiresExplicitDevelopment(){var e=base().withProperty("app.judge-url","https://ce.judge0.com");assertThrows(IllegalStateException.class,()->validate(e));assertDoesNotThrow(()->validate(e.withProperty("app.development","true")));}
 @Test void publicProviderCannotBeApprovedAsProduction(){var e=base().withProperty("app.judge-url","https://ce.judge0.com").withProperty("app.judge-security-reviewed","true");assertThrows(IllegalStateException.class,()->validate(e));}
 @Test void privateProviderRequiresOperatorReview(){var e=base().withProperty("app.judge-url","https://judge.example.test");assertThrows(IllegalStateException.class,()->validate(e));assertDoesNotThrow(()->validate(e.withProperty("app.judge-security-reviewed","true")));}
 @Test void unconfiguredProviderDoesNotPreventAdminSetup(){assertDoesNotThrow(()->validate(base()));}
 @Test void productionRefusesToStartWithoutQuizShuffleSecret(){
  var missing=base().withProperty("app.quiz-shuffle-secret","");
  var e=assertThrows(IllegalStateException.class,()->validate(missing));
  assertTrue(e.getMessage().contains("DEVFEST_QUIZ_SHUFFLE_SECRET"));
  assertThrows(IllegalStateException.class,()->validate(base().withProperty("app.quiz-shuffle-secret","too-short")));
 }
 @Test void developmentStartsWithoutQuizShuffleSecret(){assertDoesNotThrow(()->validate(base().withProperty("app.quiz-shuffle-secret","").withProperty("app.development","true")));}
}
