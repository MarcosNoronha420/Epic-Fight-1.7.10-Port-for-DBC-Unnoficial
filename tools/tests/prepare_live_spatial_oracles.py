"""Generate exact-JAR read/presentation oracles only under ignored build/.
No native binaries, decompiled sources or copied native implementations are tracked.
"""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import zipfile

p = argparse.ArgumentParser()
p.add_argument('--cfr', required=True)
p.add_argument('--java', default='java')
a = p.parse_args()
out = Path('build/dbc-live-spatial/oracle')
refs = Path('reference/native')
expected = {
    'JRMCore-v1.3.51.jar': ('0614265d665b6e60456d460255c1488377cad1200ad6bd9444128d835fc28946', [
        'JinRyuu/JRMCore/JRMCoreH', 'JinRyuu/JRMCore/JYearsCH', 'JinRyuu/JRMCore/JRMCoreConfig']),
    'JBRA-Client-v1.6.52.jar': ('541bc7a8063879e897a27acc3a5850b02a15f8e23ee086cb8d03a36a76e23634', [
        'JinRyuu/JBRA/RenderPlayerJBRA']),
    'DragonBlockC-v1.4.85.jar': ('be8c849ab107b1126018bc5b0d0d254d751129f70f3349261f1469f0c220dc9d', [
        'JinRyuu/DragonBC/common/DBCKiTech', 'JinRyuu/DragonBC/common/DBCClient',
        'JinRyuu/DragonBC/common/mod_DragonBC', 'JinRyuu/DragonBC/common/DBCConfig']),
}
for jar, (digest, classes) in expected.items():
    assert hashlib.sha256((refs / jar).read_bytes()).hexdigest() == digest, jar
    with zipfile.ZipFile(refs / jar) as z:
        for name in classes:
            target = out / 'input' / (name + '.class')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(z.read(name + '.class'))
            subprocess.run([a.java, '-jar', a.cfr, str(target), '--outputdir', str(out / 'source'), '--silent', 'true'], check=True)

def source(name):
    return (out / 'source' / (name + '.java')).read_text(encoding='utf-8')

def block(text, marker):
    start = text.index(marker)
    begin = text.index('{', start)
    depth, end = 1, begin + 1
    while depth:
        if text[end] == '{': depth += 1
        elif text[end] == '}': depth -= 1
        end += 1
    return text[start:end]

core = source('JinRyuu/JRMCore/JRMCoreH')
renderer = source('JinRyuu/JBRA/RenderPlayerJBRA')
code = 'public class NativeLiveReadOracle {\n'
for field in ['ltnb', 'StusEfcts']:
    code += 'public static String[] ' + re.search(r'\b' + field + r' = new String\[\]\{[^}]+\};', core).group(0) + '\n'
for name in ['dnsGender', 'dnsSkinT', 'dnsBodyT', 'dnsBodyC1_0', 'letToNum', 'letToNum5', 'ltn', 'ltn5']:
    code += block(core, 'public static int ' + name + '(').replace('JRMCoreH.', 'NativeLiveReadOracle.') + '\n'
code += block(core, 'public static String sa(') + '\n'

# Retain the exact native branch order/conditions. Fake GL only discards draw
# operations; the oracle reads ModelBipedDBC.y after the native source slice.
pre = block(renderer, 'protected void func_77041_b(AbstractClientPlayer p,')
start = pre.index('String[] d4 = ')
end = pre.index('ModelBipedDBC.y = 1;', start) + len('ModelBipedDBC.y = 1;')
code += '''
static String row4, row19;
static int ui, uiId;
static class EntityPlayer {boolean field_70122_E; float field_70125_A,field_70177_z;}
static class ExtendedPlayer {
  static ExtendedPlayer get(EntityPlayer p){return new ExtendedPlayer();}
  int getUIAnim(){return ui;} int getUIAnimID(){return uiId;}
}
static class ModelBipedDBC {static int y,animation;}
static class JRMCoreH {
  static String data(int i,int column,String fallback){return row4;}
  static boolean StusEfctsClient(int id,EntityPlayer p){return row19.contains(StusEfcts[id]);}
}
static class GL11 {
  static void glRotatef(float a,float b,float c,float d){}
  static void glTranslatef(float a,float b,float c){}
}
public static int presentation(String koRow,String flags,String statuses,boolean ground,int uiTime,int uiIndex){
 row4=koRow;row19=statuses;ui=uiTime;uiId=uiIndex;ModelBipedDBC.y=-1;
 EntityPlayer p=new EntityPlayer();p.field_70122_E=ground;
 int pl=0;boolean w;String s3=flags;
 do {
'''
code += pre[start:end]
code += '\n} while(false);return ModelBipedDBC.y;\n}\n}\n'
(out / 'NativeLiveReadOracle.java').write_text(code, encoding='utf-8')
assert 'static Minecraft mc' in source('JinRyuu/DragonBC/common/DBCClient')
assert 'public static boolean floating' in source('JinRyuu/DragonBC/common/DBCKiTech')
assert 'public static String[] p' in source('JinRyuu/JRMCore/JYearsCH')
print('PASS exact JAR hashes; generated native DNS and presentation oracles in build/')
