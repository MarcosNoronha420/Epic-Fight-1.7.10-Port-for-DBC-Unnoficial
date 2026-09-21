package com.nicolas.epicfight1710.anim;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.Set;

public final class ClipLibrary {
    private final Map<String, Clip> clips = new HashMap<String, Clip>();

    public Clip get(String name) { return clips.get(name); }
    public int size() { return clips.size(); }
    public boolean contains(String name) { return clips.containsKey(name); }
    public Set<String> names() { return Collections.unmodifiableSet(clips.keySet()); }

    public static ClipLibrary load(InputStream input, int jointCount) throws IOException {
        DataInputStream in=new DataInputStream(input);
        if (in.readInt()!=0x45464131) throw new IOException("Bad clips.dat magic");
        int version=in.readInt(); if(version!=2) throw new IOException("Unsupported clips.dat version "+version);
        int count=in.readInt(); ClipLibrary lib=new ClipLibrary();
        for(int c=0;c<count;c++) {
            String name=in.readUTF(); float duration=in.readFloat(); int trackCount=in.readInt();
            Clip.Track[] tracks=new Clip.Track[jointCount];
            for(int j=0;j<trackCount;j++) {
                int joint=in.readInt(); int keys=in.readInt(); float[] time=new float[keys]; float[][] trs=new float[keys][10];
                for(int k=0;k<keys;k++) {
                    time[k]=in.readFloat();
                    for(int q=0;q<10;q++) trs[k][q]=in.readFloat();
                }
                if(joint>=0 && joint<jointCount) tracks[joint]=new Clip.Track(time,trs);
            }
            lib.clips.put(name,new Clip(name,duration,tracks));
        }
        in.close(); return lib;
    }
}
