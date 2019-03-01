/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package cl.core.kernel;

import wrapper.util.CLFileReader;

/**
 *
 * @author user
 */
public class CLSource {
    public static String[] readFiles()
    {
        String source1 = CLFileReader.readFile(CLSource.class, "Common.cl");
        String source2 = CLFileReader.readFile(CLSource.class, "Matrix4f.cl");
        String source3 = CLFileReader.readFile(CLSource.class, "Material.cl");
        String source4 = CLFileReader.readFile(CLSource.class, "Primitive.cl");
        String source5 = CLFileReader.readFile(CLSource.class, "NormalBVH.cl");
        String source6 = CLFileReader.readFile(CLSource.class, "SimpleTrace.cl");
        return new String[]{source1, source2, source3, source4, source5, source6} ;
    }
}
