package user.registry.entities;


import kalix.javasdk.StatusCode;
import kalix.javasdk.annotations.*;
import kalix.javasdk.eventsourcedentity.EventSourcedEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import user.registry.Done;

import java.util.List;

/**
 * Entity representing a User.
 * <p>
 * A User has a name, a country and an email address.
 * The email address must be unique across all existing users. This is achieved with a choreography saga which ensures that
 * a user is only created if the email address is not already reserved.
 * <p>
 * This entity is protected from outside access. It can only be accessed from within this service (see the ACL annotation).
 * External access is gated and should go through the ApplicationController.
 */
@Id("id")
@TypeId("user")
@RequestMapping("/users/{id}")
@Acl(allow = @Acl.Matcher(service = "*"))
public class UserEntity extends EventSourcedEntity<UserEntity.User, UserEntity.Event> {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  // commands
  public record Create(String name, String country, String email) {
  }

  public record ChangeEmail(String newEmail) {
  }

  /**
   * It's recommended to seal the event interface.
   * As such, Kalix can detect that there are event handlers defined for each event.
   */
  public sealed interface Event {
  }

  @TypeName("user-created")
  public record UserWasCreated(String name, String country, String email) implements Event {
  }

  @TypeName("email-assigned")
  public record EmailAssigned(String newEmail) implements Event {
  }

  @TypeName("email-unassigned")
  public record EmailUnassigned(String oldEmail) implements Event {
  }

  public record User(String name, String country, String email) {

    /**
     * Handle a command to create a new user.
     * Emits a UserWasCreated event.
     */
    static public Event onCommand(Create cmd) {
      return new UserWasCreated(cmd.name(), cmd.country(), cmd.email());
    }

    static public User onEvent(UserWasCreated evt) {
      return new User(evt.name, evt.country, evt.email);
    }

    /**
     * Emits a EmailAssigned and EmailUnassigned event.
     * Emits nothing if 'changing' to the same email address.
     * <p>
     * When changing the email address, we need to emit two events:
     * one to assign the new email address and one to un-assign the old email address.
     * <p>
     * Later the UserEventsSubscriber will react to these events and update the UniqueEmailEntity accordingly.
     * The newly assigned email will be confirmed and the old email will be marked as not-in-use.
     */
    public  List<Event> onCommand(ChangeEmail cmd) {
      if (cmd.newEmail().equals(email))
        return List.of();
      else
        return List.of(
          new EmailAssigned(cmd.newEmail()),
          new EmailUnassigned(email)
        );
    }

    public User onEvent(EmailAssigned evt) {
      return new User(name, country, evt.newEmail());
    }
  }


  @PostMapping
  public Effect<Done> createUser(@RequestBody Create cmd) {

    // since the user creation depends on the email reservation, a better place to valid a incoming command
    // would be in the ApplicationController where we coordinate the two operations.
    // However, to demonstrate a failure case, we validate the command here.
    // As such, we can simulate the situation where an email is reserved, but we fail to create the user. When that
    // happens the timer defined by the UniqueEmailSubscriber will fire and un-reserve the email address.
    if (cmd.name() == null) {
      return effects().error("Name is empty", StatusCode.ErrorCode.BAD_REQUEST);
    }

    if (currentState() != null) {
      return effects().reply(Done.done());
    }

    logger.info("Creating user {}", cmd);
    return effects()
      .emitEvent(User.onCommand(cmd))
      .thenReply(__ -> Done.done());
  }

  @PutMapping("/change-email")
  public Effect<Done> changeEmail(@RequestBody ChangeEmail cmd) {
    if (currentState() == null) {
      return effects().error("User not found", StatusCode.ErrorCode.NOT_FOUND);
    }
    return effects()
      .emitEvents(currentState().onCommand(cmd))
      .thenReply(__ -> Done.done());
  }


  @GetMapping
  public Effect<User> getState() {
    if (currentState() == null) {
      return effects().error("User not found", StatusCode.ErrorCode.NOT_FOUND);
    }
    return effects().reply(currentState());
  }

  @EventHandler
  public User onEvent(UserWasCreated evt) {
    return User.onEvent(evt);
  }

  @EventHandler
  public User onEvent(EmailAssigned evt) {
    return currentState().onEvent(evt);
  }


  @EventHandler
  public User onEvent(EmailUnassigned evt) {
    return currentState();
  }

}
