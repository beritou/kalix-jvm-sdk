package user.registry.entities;

import kalix.javasdk.testkit.EventSourcedTestKit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class UserEntityTest {

  @Test
  public void testCreationAndUpdate() {
    var userTestKit = EventSourcedTestKit.of(__ -> new UserEntity());

    var creationRes = userTestKit.call(userEntity -> userEntity.createUser(new UserEntity.Create("John", "Belgium", "john@acme.com")));

    var created = creationRes.getNextEventOfType(UserEntity.UserWasCreated.class);
    assertThat(created.name()).isEqualTo("John");
    assertThat(created.email()).isEqualTo("john@acme.com");

    var updateRes = userTestKit.call(userEntity -> userEntity.changeEmail(new UserEntity.ChangeEmail("john.doe@acme.com")));
    var emailChanged = updateRes.getNextEventOfType(UserEntity.EmailAssigned.class);
    assertThat(emailChanged.newEmail()).isEqualTo("john.doe@acme.com");
  }

  @Test
  public void updateNonExistentUser() {
    var userTestKit = EventSourcedTestKit.of(__ -> new UserEntity());

    var updateRes = userTestKit.call(userService -> userService.changeEmail(new UserEntity.ChangeEmail("john.doe@acme.com")));
    assertThat(updateRes.isError()).isTrue();
    assertThat(updateRes.getError()).isEqualTo("User not found");

  }
}
