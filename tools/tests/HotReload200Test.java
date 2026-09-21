import com.nicolas.epicfight1710.client.RuntimeAssets;
import com.nicolas.epicfight1710.combat.*;
import java.nio.charset.StandardCharsets;import java.nio.file.*;
public final class HotReload200Test{
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[]a)throws Exception{
  RuntimeAssets.load();WeaponDefinitionLoader.load();long g1=WeaponCapabilityRegistry.generation();
  Path p=Paths.get("config/epicfight1710/weapon_types.json");String s=new String(Files.readAllBytes(p),StandardCharsets.UTF_8);
  String from="\"match\":[\"bravesword\",\"itemsword\"";String to="\"match\":[\"epic200_hotreload_probe\",\"bravesword\",\"itemsword\"";
  check(s.contains(from),"probe insertion point absent");Files.write(p,s.replace(from,to).getBytes(StandardCharsets.UTF_8));
  WeaponDefinitionLoader.load();long g2=WeaponCapabilityRegistry.generation();check(g2==g1+1,"generation did not increment");
  check("epicfight:sword".equals(WeaponCapabilityRegistry.resolveHierarchy("some.epic200_hotreload_probe.Item").id),"external definition not used");
  System.out.println("PASS HotReload200Test registry generation="+g1+"->"+g2+" external rule applied without rebuild");
 }
}
