package user.registry.entities;

import org.junit.jupiter.api.Test;

import kalix.javasdk.testkit.EventSourcedTestKit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UserEntityTest {

  @Test
  public void testCreationAndUpdate() {
    var userTestKit = EventSourcedTestKit.of(__ -> new UserEntity());

    var creationRes = userTestKit.call(userEntity -> userEntity.createUser(new UserEntity.Create("John", "Belgium", "john@acme.com")));

    var created = creationRes.getNextEventOfType(UserEntity.UserWasCreated.class);
    assertEquals("John", created.name());
    assertEquals("john@acme.com", created.email());

    var updateRes = userTestKit.call(userEntity -> userEntity.changeEmail(new UserEntity.ChangeEmail("john.doe@acme.com")));
    var emailChanged = updateRes.getNextEventOfType(UserEntity.EmailAssigned.class);
    assertEquals("john.doe@acme.com", emailChanged.newEmail());
  }

  @Test
  public void updateNonExistentUser() {
    var userTestKit = EventSourcedTestKit.of(__ -> new UserEntity());

    var updateRes = userTestKit.call(userService -> userService.changeEmail(new UserEntity.ChangeEmail("john.doe@acme.com")));
    assertTrue(updateRes.isError());
    assertEquals("User not found", updateRes.getError());
  }
}
