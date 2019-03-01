/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package cl.renderer;

import bitmap.display.StaticDisplay;
import bitmap.image.BitmapARGB;
import cl.core.CBoundingBox;
import cl.core.Overlay;
import cl.core.data.struct.CRay;
import cl.core.data.CPoint3;
import cl.core.data.CVector3;
import cl.core.data.struct.CMaterial;
import cl.core.device.RayDeviceMesh;
import cl.ui.mvc.model.CustomData;
import cl.ui.mvc.view.MaterialVaultTreeCell;
import cl.ui.mvc.view.TargetTreeCell;
import cl.ui.mvc.viewmodel.RenderViewModel;
import coordinate.model.OrientationModel;
import coordinate.parser.attribute.MaterialT;
import coordinate.utility.Timer;
import java.nio.file.Path;
import javafx.geometry.Bounds;
import javafx.scene.input.TransferMode;
import thread.model.KernelThread;

/**
 *
 * @author user
 */
public class SimpleRender extends KernelThread{
     
    int width = 800;
    int height = 700;
    
    int globalSize = width * height;
    int localSize = 100;
    
    BitmapARGB bitmap;
    BitmapARGB selectionBitmap;
       
    StaticDisplay display;
    
    
    OrientationModel<CPoint3, CVector3, CRay, CBoundingBox> orientation = new OrientationModel(CPoint3.class, CVector3.class);
    
    @Override
    public void execute() {
        Timer timer = new Timer();
        timer.start();
        
        RenderViewModel.getDevice().updateCamera();
        RenderViewModel.getDevice().execute();
        RenderViewModel.getDevice().readImageBuffer(buffer-> {            
            bitmap.writeColor(buffer.array(), 0, 0, width, height);              
        });
        RenderViewModel.getDevice().readGroupBuffer(buffer-> {
            RenderViewModel.overlay.copyToArray(buffer.array());              
        });        
        display.imageFill(bitmap);
        
        
        timer.end();
        
        pauseKernel();
        
        chill();
       // System.out.printf("%.2f rays/second \n", device.getTotalSize()/timer.seconds());       
    }
    
    public boolean init() 
    {   
        RenderViewModel.setDevice(new RayDeviceMesh());
        RenderViewModel.getDevice().init(width, height, globalSize, localSize);   
        bitmap = new BitmapARGB(width, height);
        selectionBitmap = new BitmapARGB(width, height, false);        
        RenderViewModel.overlay = new Overlay(width, height);
        
        return true;
    }
    
    public void launch(StaticDisplay display) {
        this.display = display;     
        display.translationDepth.addListener((observable, old_value, new_value) -> {                        
            orientation.translateDistance(RenderViewModel.getDevice().getCamera(), new_value.floatValue() * RenderViewModel.getDevice().getBound().getMaximumExtent());     
            resumeKernel();
        });
        
        display.translationXY.addListener((observable, old_value, new_value) -> {            
            orientation.rotateX(RenderViewModel.getDevice().getCamera(), (float) new_value.getX());
            orientation.rotateY(RenderViewModel.getDevice().getCamera(), (float) new_value.getY());
            resumeKernel();
        });
        
        
        init();
        
        
        display.setOnDragOver(e -> {            
            
            Bounds imageViewInScreen = display.get("base").localToScreen(display.get("base").getBoundsInLocal());
            double x = e.getScreenX() - imageViewInScreen.getMinX();
            double y = e.getScreenY() - imageViewInScreen.getMinY();
            
            if(!(e.getGestureSource() instanceof MaterialVaultTreeCell))            
                if(e.getDragboard().getContent(CustomData.getFormat()) instanceof CustomData)
                {         
                    if(RenderViewModel.overlay.isInstance(x, y))
                        e.acceptTransferModes(TransferMode.COPY_OR_MOVE);             
                }
                       
            int instance = RenderViewModel.overlay.get(x, y);
            selectionBitmap = RenderViewModel.overlay.getDragOverlay(instance);
            display.imageFillSelection(selectionBitmap);
           
        });
        
        display.setOnDragEntered(e -> {
            Bounds imageViewInScreen = display.get("base").localToScreen(display.get("base").getBoundsInLocal());
            double x = e.getScreenX() - imageViewInScreen.getMinX();
            double y = e.getScreenY() - imageViewInScreen.getMinY();
                       
            int instance = RenderViewModel.overlay.get(x, y);
            selectionBitmap = RenderViewModel.overlay.getDragOverlay(instance);
            display.imageFillSelection(selectionBitmap);
           
        });
                
        display.setOnDragExited(e -> {
            selectionBitmap = RenderViewModel.overlay.getNull();
            display.imageFillSelection(selectionBitmap);
        });
        
        display.setOnDragDropped(e -> {
            Bounds imageViewInScreen = display.get("base").localToScreen(display.get("base").getBoundsInLocal());
            double x = e.getScreenX() - imageViewInScreen.getMinX();
            double y = e.getScreenY() - imageViewInScreen.getMinY();
            
            if(e.getGestureSource() instanceof TargetTreeCell)
            {
                CustomData data = (CustomData) e.getDragboard().getContent(CustomData.getFormat());
                MaterialT mat = (MaterialT) data.getData();                
                CMaterial cmat = new CMaterial();
                int cmatIndex = RenderViewModel.overlay.get(x, y);
                cmat.setDiffuse(mat.dr, mat.dg, mat.db);                
                RenderViewModel.getDevice().setMaterial(cmatIndex, cmat);                
                resumeKernel();
            }
        });
        
               
        startKernel();
    }
    
    public boolean close() {
        return true;
    }
    
    
    public void initMesh(Path path)
    {
        RenderViewModel.getDevice().initMesh(path);
    }
}
