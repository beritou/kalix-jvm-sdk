package user.registry.subscribers;


import kalix.javasdk.action.Action;
import kalix.javasdk.annotations.Subscribe;
import kalix.javasdk.client.ComponentClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import user.registry.api.Done;
import user.registry.entities.UniqueEmailEntity;
import user.registry.entities.UserEntity;

/**
 * This Action plays the role of a subscriber to the UserEntity.
 * <p>
 * In the choreography, this subscriber will react to events (facts) produced by the UserEntity and modify the
 * UniqueEmailEntity accordingly. Either by confirming or un-reserving the email address.
 */
@Subscribe.EventSourcedEntity(value = UserEntity.class)
public class UserEventsSubscriber extends Action {

  private final ComponentClient client;
  private final Logger logger = LoggerFactory.getLogger(getClass());

  public UserEventsSubscriber(ComponentClient client) {
    this.client = client;
  }

  /**
   * This method is called and a user is created or when a new email address is assigned to a user.
   * It will hit the UniqueEmailEntity to confirm the email address.
   */
  private Effect<Done> confirmEmail(String emailAddress) {
    logger.info("User got a new email address assigned: {}, confirming new address address", emailAddress);
    var confirmation = client.forValueEntity(emailAddress).call(UniqueEmailEntity::confirm);
    return effects().forward(confirmation);
  }

  public Effect<Done> onEvent(UserEntity.UserWasCreated evt) {
    return confirmEmail(evt.email());
  }

  public Effect<Done> onEvent(UserEntity.EmailAssigned evt) {
    return confirmEmail(evt.newEmail());
  }

  /**
   * When a user stops to use an email address, this method gets called and un-reserves the email address.
   */
  public Effect<Done> onEvent(UserEntity.EmailUnassigned evt) {
    logger.info("Old email address unassigned: {}, deleting unique email address record", evt);
    var unreserved = client.forValueEntity(evt.oldEmail()).call(UniqueEmailEntity::delete);
    return effects().forward(unreserved);
  }
}
