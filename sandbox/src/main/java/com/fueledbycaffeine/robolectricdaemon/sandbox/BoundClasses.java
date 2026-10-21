package com.fueledbycaffeine.robolectricdaemon.sandbox;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Checks whether classes kept by Robolectric's sandbox loader can be reused between test runs.
 *
 * <p>A class is safe to reuse only when it has no state to reinitialize and it, along with the
 * project classes it refers to, has not changed. ASM is supplied by Robolectric at runtime.
 */
public final class BoundClasses {
  private static final String STRING = "Ljava/lang/String;";
  private static final int CONSTANT_CLASS_TAG = 7;

  private BoundClasses() {}

  /**
   * True if initializing the class can't create state a fresh JVM would recreate: every static
   * field is a compile-time constant (a final primitive or String with a {@code ConstantValue}) and
   * there is no static initializer. Resource classes ({@code R} and {@code R$type}) may also have
   * {@code int} and {@code int[]} resource IDs set by their initializer; AGP generates those
   * non-final for unit tests, but nothing writes them.
   */
  public static boolean isConstantOnly(byte[] bytes) {
    ClassReader reader = new ClassReader(bytes);
    ConstantOnlyVisitor visitor = new ConstantOnlyVisitor(isResourceClass(reader.getClassName()));
    reader.accept(
        visitor,
        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return visitor.isConstantOnly();
  }

  /** Binary names of classes this class refers to, including array element types. */
  public static List<String> references(byte[] bytes) {
    ClassReader reader = new ClassReader(bytes);
    char[] buffer = new char[reader.getMaxStringLength()];
    List<String> names = new ArrayList<>();
    for (int item = 1; item < reader.getItemCount(); item++) {
      int offset = reader.getItem(item);
      // ASM gives the payload offset; the class-entry tag is the byte immediately before it.
      if (offset > 0 && reader.readByte(offset - 1) == CONSTANT_CLASS_TAG) {
        String name = referencedClassName(reader.readUTF8(offset, buffer));
        if (name != null) {
          names.add(name);
        }
      }
    }
    return names;
  }

  private static boolean isResourceClass(String className) {
    String simpleName = className.substring(className.lastIndexOf('/') + 1);
    return simpleName.equals("R") || simpleName.startsWith("R$");
  }

  private static String referencedClassName(String internalName) {
    Type type = Type.getObjectType(internalName);
    if (type.getSort() == Type.ARRAY) {
      type = type.getElementType();
    }
    return type.getSort() == Type.OBJECT ? type.getClassName() : null;
  }

  private static boolean isConstantField(
      int access, String descriptor, Object value, boolean resourceClass) {
    if (resourceClass && (descriptor.equals("I") || descriptor.equals("[I"))) {
      return true;
    }
    int sort = Type.getType(descriptor).getSort();
    boolean primitive = sort >= Type.BOOLEAN && sort <= Type.DOUBLE;
    return (access & Opcodes.ACC_FINAL) != 0
        && value != null
        && (primitive || descriptor.equals(STRING));
  }

  private static final class ConstantOnlyVisitor extends ClassVisitor {
    private final boolean resourceClass;
    private boolean constantOnly = true;

    ConstantOnlyVisitor(boolean resourceClass) {
      super(Opcodes.ASM9);
      this.resourceClass = resourceClass;
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String descriptor, String signature, Object value) {
      if ((access & Opcodes.ACC_STATIC) != 0
          && !isConstantField(access, descriptor, value, resourceClass)) {
        constantOnly = false;
      }
      return null;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      if (name.equals("<clinit>") && !resourceClass) {
        constantOnly = false;
      }
      return null;
    }

    boolean isConstantOnly() {
      return constantOnly;
    }
  }
}
