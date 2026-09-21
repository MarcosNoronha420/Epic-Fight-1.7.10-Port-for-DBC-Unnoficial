import com.nicolas.epicfight1710.client.RuntimeAssets;
import com.nicolas.epicfight1710.combat.WeaponDefinitionLoader;
import com.nicolas.epicfight1710.combat.WeaponCapabilityRegistry;
public final class EmbeddedLoad200Test{
 public static void main(String[]a){RuntimeAssets.load();WeaponDefinitionLoader.load();if(WeaponCapabilityRegistry.typeCount()!=8)throw new AssertionError();System.out.println("PASS EmbeddedLoad200Test assets+clip validation+embedded/external loader types="+WeaponCapabilityRegistry.typeCount()+" rules="+WeaponCapabilityRegistry.itemRuleCount());}
}
