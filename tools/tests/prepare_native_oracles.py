"""Hash-checked local JAR audit + generated native math oracles; output only build/.
No native binary/decompiled source is copied into tracked fixtures.
"""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import zipfile

p = argparse.ArgumentParser()
p.add_argument("--cfr", required=True)
p.add_argument("--java", default="java")
a = p.parse_args()
out = Path("build/native-oracle")
out.mkdir(parents=True, exist_ok=True)
refs = next(x for x in Path.cwd().iterdir() if x.name.lower() == "reference")
refs = next(x for x in refs.iterdir() if x.name.lower() == "native")
expected = {
    "DragonBlockC-v1.4.85.jar": "be8c849ab107b1126018bc5b0d0d254d751129f70f3349261f1469f0c220dc9d",
    "JBRA-Client-v1.6.52.jar": "541bc7a8063879e897a27acc3a5850b02a15f8e23ee086cb8d03a36a76e23634",
    "JRMCore-v1.3.51.jar": "0614265d665b6e60456d460255c1488377cad1200ad6bd9444128d835fc28946",
}
for name, digest in expected.items():
    assert hashlib.sha256((refs / name).read_bytes()).hexdigest() == digest, name + " version/hash mismatch"

classes = {
    "JBRA-Client-v1.6.52.jar": ["JinRyuu/JBRA/RenderPlayerJBRA", "JinRyuu/JBRA/ModelBipedDBC", "JinRyuu/JBRA/ModelRendererJBRA"],
    "JRMCore-v1.3.51.jar": ["JinRyuu/JRMCore/entity/ModelBipedBody", "JinRyuu/JRMCore/JRMCoreHDBC", "JinRyuu/JRMCore/JRMCoreH", "JinRyuu/JRMCore/i/ExtendedPlayer"],
    "DragonBlockC-v1.4.85.jar": ["JinRyuu/DragonBC/common/DBCClientTickHandler", "JinRyuu/DragonBC/common/Render/ModelBipedDBC"],
}
for jar, names in classes.items():
    with zipfile.ZipFile(refs / jar) as z:
        for name in names:
            target = out / "classes-input" / (name + ".class")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(z.read(name + ".class"))
            subprocess.run([a.java, "-jar", a.cfr, str(target), "--outputdir", str(out / "source"), "--silent", "true"], check=True)

def read(name):
    return (out / "source" / (name + ".java")).read_text(encoding="utf-8")

def block(text, marker):
    start = text.index(marker)
    begin = text.index("{", start)
    depth = 1
    end = begin + 1
    while depth:
        if text[end] == "{": depth += 1
        if text[end] == "}": depth -= 1
        end += 1
    return text[start:end]

body = read("JinRyuu/JRMCore/entity/ModelBipedBody")
method = block(body, "public void renderBody(float par7)")
# Preserve native code through the last major limb draw. Cosmetic subgroups are
# deliberately outside the provider. This is a source slice, not a native draw.
end_marker = "this.leftleg.func_78785_a(f5);"
end = method.index("GL11.glPopMatrix();", method.index(end_marker)) + len("GL11.glPopMatrix();")
body_draw = method.index("this.body.func_78785_a(f5);")
body_start = method.rfind("GL11.glPushMatrix();", 0, body_draw)
body_end = method.index("GL11.glPopMatrix();", body_draw) + len("GL11.glPopMatrix();")
method = method[:end] + "\n" + method[body_start:body_end] + "\n}\n}\n"
fields = ["field_78116_c", "field_78115_e", "field_78112_f", "field_78113_g", "field_78123_h", "field_78124_i", "Brightarm", "Bleftarm", "rightleg", "leftleg", "body"]
code = "import java.util.*; public final class NativeBodyOracle {\n"
code += "public static float f; public static int g; public boolean field_78091_s,field_78117_n;\n"
for field in fields:
    code += 'Part ' + field + '=new Part("' + field + '");\n'
code += "public static Map<String,float[]> output=new HashMap<String,float[]>();\n"
code += 'static class Part {String name; Part(String n){name=n;} void func_78785_a(float s){output.put(name,GL11.ops.clone());}}\n'
code += 'static class GL11 {static float[] ops; static void glPushMatrix(){ops=new float[6];} static void glPopMatrix(){} static void glScalef(float x,float y,float z){ops[0]=x;ops[1]=y;ops[2]=z;} static void glTranslatef(float x,float y,float z){ops[3]=x;ops[4]=y;ops[5]=z;}}\n'
code += method + "}\n"
(out / "NativeBodyOracle.java").write_text(code, encoding="utf-8")

helper = read("JinRyuu/JRMCore/JRMCoreHDBC")
core = read("JinRyuu/JRMCore/JRMCoreH")
renderer = read("JinRyuu/JBRA/RenderPlayerJBRA")
code = "public final class NativeFormulaOracle {\n"
for marker in ["public static float DBCsizeBasedOnCns2(int[]", "public static float DBCsizeBasedOnRace(int race, int state, boolean", "public static float DBCsizeBasedOnRace2(int race, int state, boolean", "public static boolean godKiUserBase(int", "public static int nRP9ea()", "public static int d5keKm(String"]:
    code += block(helper, marker).replace("JRMCoreHDBC.", "NativeFormulaOracle.") + "\n"
code += "static boolean cosmic; static boolean DBCgetConfigGodformCosm(){return cosmic;}\n"
code += "static class mod_DragonBC {static boolean ConsSizeChangeOn,TransSizeChangeOn;}\n"
code += "static class JRMCoreConfig {static int tmx;}\n"
code += "static class JRMCoreH {static boolean divine; static String[] dat10; static boolean DBC(){return true;} static boolean isPowerTypeChakra(int p){return p==2;} static boolean StusEfctsClient(int s,int i){return divine;}\n"
for name in ["isRaceSaiyan", "isRaceNamekian", "isRaceHuman", "isRaceHumanOrNamekian", "isRaceArcosian", "isRaceMajin", "rc_sai", "rc_humNam", "rc_hum", "rc_nam", "rc_arc", "rSai"]:
    code += block(core, "public static boolean " + name + "(int") + "\n"
tables = ["TransSaiBlk", "TransSaiSz", "TransFrBlk", "TransFrSz", "TransHmBlk", "TransHmSz", "TransNaBlk", "TransNaSz", "TransMaBulk", "TransMaSize"]
for name in tables:
    expr = re.search(r"\b" + name + r" = new float\[\]\{[^}]+\};", core).group(0)
    code += "static float[] " + expr + "\n"
code += "}\n"
pre = block(renderer, "protected void func_77041_b(AbstractClientPlayer p,")
prefix = pre[pre.index("boolean noC;"):pre.index("if (JRMCoreH.plyrs")]
equations = block(pre, "if (JRMCoreH.DBC()) {").replace("JRMCoreHDBC.", "NativeFormulaOracle.")
code += "public float[] scale(int gen,int raceID,int form,int con,int release,boolean divineFlag){\n"
code += 'String[] s={""+raceID,"","1"},s2={""+form}; int[] PlyrAttrbts={0,0,con}; int pl=0; JRMCoreH.dat10=new String[]{""+release}; JRMCoreH.divine=divineFlag;\n'
code += prefix + "float f1r=f1;\n" + equations + "\nreturn new float[]{f1*f2*f3,f1*f3,f1*f2*f3};}\n"
code += block(renderer, "byte b(String n)") + "\n"
code += 'public static float[][] tables(boolean size){return size?new float[][]{JRMCoreH.TransHmSz,JRMCoreH.TransSaiSz,JRMCoreH.TransSaiSz,JRMCoreH.TransNaSz,JRMCoreH.TransFrSz,JRMCoreH.TransMaSize}:new float[][]{JRMCoreH.TransHmBlk,JRMCoreH.TransSaiBlk,JRMCoreH.TransSaiBlk,JRMCoreH.TransNaBlk,JRMCoreH.TransFrBlk,JRMCoreH.TransMaBulk};}\n'
code += 'public static void configure(boolean con,boolean trans,boolean god,int max){mod_DragonBC.ConsSizeChangeOn=con;mod_DragonBC.TransSizeChangeOn=trans;cosmic=god;JRMCoreConfig.tmx=max;}\n}\n'
(out / "NativeFormulaOracle.java").write_text(code, encoding="utf-8")
print("PASS local JAR hashes; generated native equation slices and default-table fixtures in build/native-oracle")
