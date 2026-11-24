import com.sun.tools.attach.VirtualMachine;

/** Loads the probe agent into the process id given as the first argument. */
public final class Attach {
  public static void main(final String[] arguments) throws Exception {
    final var virtualMachine = VirtualMachine.attach(arguments[0]);
    try {
      virtualMachine.loadAgent(arguments[1], arguments[2]);
    } finally {
      virtualMachine.detach();
    }
  }
}
