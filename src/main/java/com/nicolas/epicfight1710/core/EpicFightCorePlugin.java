package com.nicolas.epicfight1710.core;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(10000)
@IFMLLoadingPlugin.TransformerExclusions({"com.nicolas.epicfight1710.core"})
public final class EpicFightCorePlugin implements IFMLLoadingPlugin {
    public String[] getASMTransformerClass(){return new String[]{"com.nicolas.epicfight1710.core.EpicFightTransformer"};}
    public String getModContainerClass(){return null;}
    public String getSetupClass(){return null;}
    public void injectData(Map<String,Object> data){}
    public String getAccessTransformerClass(){return null;}
}
