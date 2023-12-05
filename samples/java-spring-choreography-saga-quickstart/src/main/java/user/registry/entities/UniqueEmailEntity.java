package user.registry.entities;


import kalix.javasdk.annotations.Acl;
import kalix.javasdk.annotations.Id;
import kalix.javasdk.annotations.TypeId;
import kalix.javasdk.valueentity.ValueEntity;
import kalix.javasdk.valueentity.ValueEntityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import user.registry.api.Done;

import java.util.Optional;

/**
 * This entity works as a barrier to ensure that an email address is only used once.
 * In the process of creating a user, the email address is reserved.
 * If the user creation fails, the email address is un-reserved. Otherwise, it is confirmed.
 * <p>
 * If, while creating a user, the email address is already reserved, the user creation fails.
 */
@Id("id")
@TypeId("unique-address")
@RequestMapping("/unique-emails/{id}")
// Only allow access to this entity from inside the service (i.e. from the ApplicationController)
@Acl(allow = @Acl.Matcher(service = "*"))
public class UniqueEmailEntity extends ValueEntity<UniqueEmailEntity.UniqueEmail> {

  private final String address;
  private Logger logger = LoggerFactory.getLogger(getClass());

  public UniqueEmailEntity(ValueEntityContext context) {
    this.address = context.entityId();
  }

  public enum Status {
    NOT_USED,
    RESERVED,
    CONFIRMED
  }

  /**
   * This is the initial state of the entity.
   * When the entity is created, it is not used.
   * If can be reverted to this state by calling the delete() method.
   */
  private final UniqueEmail notInUse() {
    return new UniqueEmail(address, Status.NOT_USED, Optional.empty());
  }

  /**
   * For the initial state, we return an email address that is not in use.
   */
  @Override
  public UniqueEmail emptyState() {
    return notInUse();
  }



  public record UniqueEmail(String address, Status status, Optional<String> ownerId) {

    public boolean sameOwner(String ownerId) {
      return this.ownerId.isPresent() && this.ownerId.get().equals(ownerId);
    }

    public boolean notSameOwner(String ownerId) {
      return !sameOwner(ownerId);
    }

    public UniqueEmail asConfirmed() {
      return new UniqueEmail(address, Status.CONFIRMED, ownerId);
    }

    public boolean isConfirmed() {
      return status == Status.CONFIRMED;
    }

    public boolean isInUse() {
      return status != Status.NOT_USED;
    }
    public boolean isNotInUse() {
      return !isInUse();
    }

    public boolean isReserved() {
      return status == Status.RESERVED;
    }
  }

  public record ReserveEmail(String address, String ownerId) {
  }

  /**
   * This method reserves an email address.
   * If the email address is already in use (reserved or confirmed) and we are trying to reserve it for a different user,
   * the call will fail.
   * <p>
   * If we are trying to reserve the email address for the same user, the call will succeed, but won't change the email state.
   * <p>
   * If the email address is not in use at all, the call will succeed and the email address will be reserved for the given user.
   */
  @PostMapping("/reserve")
  public Effect<Done> reserve(@RequestBody ReserveEmail cmd) {
    if (currentState().isInUse() && currentState().notSameOwner(cmd.ownerId)) {
      return effects().error("Email is already reserved");
    }

    if (currentState().sameOwner(cmd.ownerId)) {
      return effects().reply(Done.done());
    }

    logger.info("Reserving address '{}'", cmd.address());
    return effects()
      .updateState(new UniqueEmail(cmd.address, Status.RESERVED, Optional.of(cmd.ownerId)))
      .thenReply(Done.done());
  }

  /**
   * This method is called when the email address is confirmed.
   * This happens when UserEventsSubscriber sees a UserWasCreated event or an EmailAssigned event.
   */
  @PostMapping("/confirm")
  public Effect<Done> confirm() {
    if (currentState().isReserved()) {
      logger.info("Email is reserved, confirming address '{}'", currentState().address);
      return effects()
        .updateState(currentState().asConfirmed())
        .thenReply(Done.done());
    } else {
      logger.info("Email status is not reserved. Ignoring confirmation request.");
      return effects().reply(Done.done());
    }
  }


  /**
   * This method is called when the email address is no longer used.
   * It's only called from the scheduled timer (see UniqueEmailSubscriber).
   * <p>
   * When the timer fires, we un-reserve the email address but only if it is not confirmed.
   * If it's already confirm or if in the meantime the email is not in use anymore, the call has no effect.
   */
  @PostMapping()
  public Effect<Done> unReserve() {
    if (currentState().isReserved()) {
      logger.info("Un-reserving address '{}'", currentState().address);
      // when un-reserving, we go back to the initial state (not in use)
      return effects()
        .updateState(notInUse())
        .thenReply(Done.done());
    } else {
      return effects().reply(Done.done());
    }
  }

  /**
   * This method is called when the email address is no longer used.
   * this method is called from the UserEventsSubscriber when a user stops using an email address.
   * <p>
   * It doesn't verify if the email address is reserved or confirmed.
   * Whatever the status is, it will move back to 'not-in-use'.
   * Strictly speaking, since this method is only called from UserEventsSubscriber, it will never be called when the email
   * is still in RESERVED state. The ordering of events is guaranteed, so first the UserWasCreated event will be processed
   * confirming the email, then later an EmailUnassigned event might be emitted and this method will be called.
   */
  @DeleteMapping()
  public Effect<Done> delete() {
    logger.info("Deleting address '{}'", currentState().address);
    return effects()
      .updateState(notInUse())
      .thenReply(Done.done());
  }


  @GetMapping
  public Effect<UniqueEmail> getState() {
    return effects().reply(currentState());
  }

}
