package user.registry;

/**
 * A type to use for acknowledging that a command was handled.
 */
public record Done() {

  public static Done done() {
    return new Done();
  }

}
