package user.registry.entities;


import kalix.javasdk.testkit.ValueEntityTestKit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class UniqueEmailEntityTest {

  @Test
  public void testReserveAndConfirm() {
    var emailTestKit = ValueEntityTestKit.of(UniqueEmailEntity::new);
    reserveEmail(emailTestKit, "joe@acme.com", "1");
    confirmEmail(emailTestKit);
  }

  @Test
  public void testReserveAndUnReserve() {
    var emailTestKit = ValueEntityTestKit.of(UniqueEmailEntity::new);
    reserveEmail(emailTestKit, "joe@acme.com", "1");
    unreserveEmail(emailTestKit);

    var state = emailTestKit.call(UniqueEmailEntity::getState).getReply();
    assertTrue(state.isNotInUse());
  }

  @Test
  public void testReserveConfirmAndUnReserve() {
    var emailTestKit = ValueEntityTestKit.of(UniqueEmailEntity::new);
    reserveEmail(emailTestKit, "joe@acme.com", "1");
    confirmEmail(emailTestKit);

    // unReserving a confirmed has no effect
    unreserveEmail(emailTestKit);
    var state = emailTestKit.call(UniqueEmailEntity::getState).getReply();
    assertTrue(state.isInUse());
    assertTrue(state.isConfirmed());
  }

  @Test
  public void testReserveAndDeleting() {
    var emailTestKit = ValueEntityTestKit.of(UniqueEmailEntity::new);
    reserveEmail(emailTestKit, "joe@acme.com", "1");
    deleteEmail(emailTestKit);
  }

  @Test
  public void testReserveConfirmAndDeleting() {
    var emailTestKit = ValueEntityTestKit.of(UniqueEmailEntity::new);
    reserveEmail(emailTestKit, "joe@acme.com", "1");
    confirmEmail(emailTestKit);
    deleteEmail(emailTestKit);
  }

  private static void confirmEmail(ValueEntityTestKit<UniqueEmailEntity.UniqueEmail, UniqueEmailEntity> emailTestKit) {
    var confirmedRes = emailTestKit.call(UniqueEmailEntity::confirm);
    assertTrue(confirmedRes.isReply());
    assertTrue(confirmedRes.stateWasUpdated());
    var state = emailTestKit.call(UniqueEmailEntity::getState).getReply();
    assertTrue(state.isConfirmed());
  }

  private static void reserveEmail(ValueEntityTestKit<UniqueEmailEntity.UniqueEmail, UniqueEmailEntity> emailTestKit, String email, String ownerId) {
    var reserveCmd = new UniqueEmailEntity.ReserveEmail(email, ownerId);
    var reservedRes =
      emailTestKit.call(emailEntity -> emailEntity.reserve(reserveCmd));
    assertTrue(reservedRes.isReply());
    assertTrue(reservedRes.stateWasUpdated());

    var state = emailTestKit.call(UniqueEmailEntity::getState).getReply();
    assertTrue(state.isReserved());
  }

  private static void deleteEmail(ValueEntityTestKit<UniqueEmailEntity.UniqueEmail, UniqueEmailEntity> emailTestKit) {
    var reservedRes =
      emailTestKit.call(UniqueEmailEntity::delete);
    assertTrue(reservedRes.isReply());
    assertTrue(reservedRes.stateWasUpdated());

    var state = emailTestKit.call(UniqueEmailEntity::getState).getReply();
    assertTrue(state.isNotInUse());
  }

  private static void unreserveEmail(ValueEntityTestKit<UniqueEmailEntity.UniqueEmail, UniqueEmailEntity> emailTestKit) {
    var reservedRes =
      emailTestKit.call(UniqueEmailEntity::unReserve);
    assertTrue(reservedRes.isReply());
  }


}
