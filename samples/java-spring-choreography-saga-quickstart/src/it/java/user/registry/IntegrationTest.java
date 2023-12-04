package user.registry;

import com.google.protobuf.any.Any;
import kalix.javasdk.DeferredCall;
import kalix.spring.testkit.KalixIntegrationTestKitSupport;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import user.registry.api.ApplicationController;
import user.registry.entities.UserEntity;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

/**
 * This is a skeleton for implementing integration tests for a Kalix application built with the Java SDK.
 * <p>
 * This test will initiate a Kalix Proxy using testcontainers and therefore it's required to have Docker installed
 * on your machine. This test will also start your Spring Boot application.
 * <p>
 * Since this is an integration tests, it interacts with the application using a WebClient
 * (already configured and provided automatically through injection).
 */
@SpringBootTest(classes = Main.class)
public class IntegrationTest extends KalixIntegrationTestKitSupport {


  /**
   * This is a test for a successful user creation.
   * User is correctly created and email is marked as confirmed.
   */
  @Test
  public void testSuccessfulUserCreation() throws Exception {
    var callGetEmailInfo =
      componentClient.forAction()
        .call(ApplicationController::getEmailInfo)
        .params("doe@acme.com");

    Duration timeout = Duration.ofSeconds(3);
    await()
      .ignoreExceptions()
      .atMost(timeout)
      .untilAsserted(() -> {
        var res = execute(callGetEmailInfo);
        assertThat(res.ownerId()).isEmpty();
        assertThat(res.status()).isEqualTo("NOT_USED");
      });

    var callCreateUser =
      componentClient.forAction()
        .call(ApplicationController::createUser)
        .params("001", new UserEntity.Create("John Doe", "US", "doe@acme.com"));
    await()
      .ignoreExceptions()
      .atMost(timeout)
      .untilAsserted(() -> assertThat(callCreateUser.execute()).succeedsWithin(timeout));

    // get email once more and check it's now confirmed
    await()
      .ignoreExceptions()
      .atMost(timeout)
      .untilAsserted(() -> {
        var res = execute(callGetEmailInfo);
        assertThat(res.ownerId()).isNotEmpty();
        assertThat(res.status()).isEqualTo("CONFIRMED");
      });

  }

  /**
   * This is a test for the failure scenario
   * The email is reserved, but we fail to create the user.
   * Timer will fire and un-reserve the email.
   */
  @Test
  public void testUserCreationFailureDueToInvalidInput() throws Exception {
    var callGetEmailInfo =
      componentClient.forAction()
        .call(ApplicationController::getEmailInfo)
        .params("invalid@acme.com");

    Duration timeout = Duration.ofSeconds(3);
    await()
      .ignoreExceptions()
      .atMost(timeout)
      .untilAsserted(() -> {
        var res = execute(callGetEmailInfo);
        assertThat(res.ownerId()).isEmpty();
        assertThat(res.status()).isEqualTo("NOT_USED");
      });

    var callCreateUser =
      componentClient.forAction()
        .call(ApplicationController::createUser)
        // this user creation will fail because user's name is not provided
        .params("002", new UserEntity.Create(null, "US", "invalid@acme.com"));
    await()
      .ignoreExceptions()
      .atMost(timeout)
      .untilAsserted(() -> assertThat(callCreateUser.execute()).failsWithin(timeout));

    // email will be reserved for a while, then it will be released
    await()
      .ignoreExceptions()
      .atMost(timeout)
      .untilAsserted(() -> {
        var res = execute(callGetEmailInfo);
        assertThat(res.ownerId()).isNotEmpty();
        assertThat(res.status()).isEqualTo("RESERVED");
      });

    await()
      .ignoreExceptions()
      // timer will fire in 3 seconds and un-reserve the email
      // see it/resources/application.conf for the configuration
      // we only start to polling after 3 seconds to give the timer a chance to fire
      .between(Duration.ofSeconds(3), Duration.ofSeconds(6))
      .untilAsserted(() -> {
        var res = execute(callGetEmailInfo);
        assertThat(res.ownerId()).isEmpty();
        assertThat(res.status()).isEqualTo("NOT_USED");
      });
  }

  /* helper method to execute and get the results from a DeferredCall */
  private <T> T execute(DeferredCall<Any, T> deferredCall) {
    try {
      return deferredCall.execute().toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      throw new RuntimeException(e);
    }
  }
}