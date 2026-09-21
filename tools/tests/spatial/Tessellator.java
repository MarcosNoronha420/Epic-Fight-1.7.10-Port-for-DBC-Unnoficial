package net.minecraft.client.renderer;
/** Compile-only native draw boundary. This test must never draw. Not packaged. */
public final class Tessellator {
    public static final Tessellator field_78398_a=new Tessellator();
    public void func_78371_b(int mode){throw new AssertionError("Native draw attempted");}
    public void func_78374_a(double x,double y,double z,double u,double v){throw new AssertionError("Native vertex attempted");}
    public void func_78375_b(float x,float y,float z){throw new AssertionError("Native normal attempted");}
    public int func_78381_a(){throw new AssertionError("Native draw attempted");}
}
