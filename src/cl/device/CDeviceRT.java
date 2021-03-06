/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package cl.device;

import cl.struct.CTextureData;
import cl.struct.CCameraModel;
import cl.struct.CMaterial;
import cl.struct.CBSDF;
import cl.struct.CCamera;
import cl.struct.CIntersection;
import cl.struct.CRay;
import cl.struct.CBound;
import bitmap.display.BlendDisplay;
import bitmap.image.BitmapARGB;
import static cl.abstracts.MambaAPIInterface.ImageType.RAYTRACE_IMAGE;
import cl.abstracts.RayDeviceInterface;
import cl.algorithms.CEnvironment;
import cl.data.CPoint2;
import cl.data.CPoint3;
import cl.data.CVector3;
import cl.scene.CMesh;
import cl.scene.CNormalBVH;
import cl.ui.fx.Overlay;
import cl.algorithms.CTextureApplyPass;
import cl.ui.fx.main.TracerAPI;
import coordinate.model.OrientationModel;
import javafx.application.Platform;
import thread.model.LambdaThread;
import wrapper.core.CKernel;
import wrapper.core.CMemory;
import static wrapper.core.CMemory.READ_WRITE;
import wrapper.core.OpenCLConfiguration;
import wrapper.core.memory.values.IntValue;

/**
 *
 * @author user
 */
public class CDeviceRT implements RayDeviceInterface<
        TracerAPI, 
        BlendDisplay, 
        CMaterial, 
        CMesh,
        CNormalBVH,
        CBound,
        CCameraModel, 
        CCamera> {
    OpenCLConfiguration configuration;
    BlendDisplay display;
    
    //API
    TracerAPI api;
    
    //render thread
    LambdaThread raytraceThread = new LambdaThread();
    
    CCameraModel cameraModel = new CCameraModel(new CPoint3(0, 0, -9), new CPoint3(), new CVector3(0, 1, 0), 45);
    CMesh mesh = null;
    CNormalBVH bvh = null;
    
    final int width, height;
    private final BitmapARGB raytraceBitmap;
    private final Overlay overlay;
    
    //global and local size
    int globalWorkSize, localWorkSize;
    
    //priority bound
    CBound priorityBound;
    
    //CL
    CMemory<IntValue> imageBuffer = null;      
    CMemory<CCamera> cameraBuffer = null;    
    CMemory<CRay> raysBuffer = null;
    CMemory<CIntersection> isectBuffer = null;
    CMemory<IntValue> count = null;
    CMemory<IntValue> groupBuffer = null;
    CMemory<CTextureData> texBuffer = null;
    CMemory<CBSDF> bsdfBuffer = null;
    
    CKernel initCameraRaysKernel = null;
    CKernel intersectPrimitivesKernel = null;
    CKernel fastShadeKernel = null;
    CKernel backgroundShadeKernel = null;
    CKernel updateGroupbufferShadeImageKernel = null;
    CKernel textureInitPassKernel = null;
    CKernel updateToTextureColorRTKernel = null;
    CKernel setupBSDFRaytraceKernel = null;
    
    CTextureApplyPass texApplyPass = null;
    
    CEnvironment envmap = null;
       
    public CDeviceRT(int w, int h)
    {
        this.width = w; 
        this.height = h;
        this.raytraceBitmap = new BitmapARGB(w, h);
        this.overlay = new Overlay(w, h);
        this.globalWorkSize = width * height;
        this.localWorkSize = 250;
    }
    
    
    
    public void initBuffers()
    {
        raysBuffer          = configuration.createBufferB(CRay.class, globalWorkSize, READ_WRITE);
        cameraBuffer        = configuration.createBufferB(CCamera.class, 1, READ_WRITE);
        count               = configuration.createFromI(IntValue.class, new int[]{globalWorkSize}, READ_WRITE);
        isectBuffer         = configuration.createBufferB(CIntersection.class, globalWorkSize, READ_WRITE);
        imageBuffer         = configuration.createBufferI(IntValue.class, globalWorkSize, READ_WRITE);        
        groupBuffer         = configuration.createBufferI(IntValue.class, globalWorkSize, READ_WRITE);
        texBuffer           = configuration.createBufferI(CTextureData.class, globalWorkSize, READ_WRITE);
        bsdfBuffer          = configuration.createBufferB(CBSDF.class, globalWorkSize, READ_WRITE);
        texApplyPass = new CTextureApplyPass(api, texBuffer, count);
    }
    
    public void initKernels()
    {    
        initCameraRaysKernel                = configuration.createKernel("InitCameraRayData", cameraBuffer, raysBuffer);
        intersectPrimitivesKernel           = configuration.createKernel("IntersectPrimitives", raysBuffer, isectBuffer, count, mesh.clPoints(), mesh.clTexCoords(), mesh.clNormals(), mesh.clFaces(), mesh.clSize(), bvh.getNodes(), bvh.getBounds());
        fastShadeKernel                     = configuration.createKernel("fastShade", isectBuffer, bsdfBuffer, imageBuffer);
        backgroundShadeKernel               = configuration.createKernel("backgroundShade", isectBuffer, cameraBuffer, imageBuffer, raysBuffer, envmap.getRgbCL(), envmap.getCEnvGrid());
        updateGroupbufferShadeImageKernel   = api.getConfigurationCL().createKernel("updateGroupbufferShadeImage", isectBuffer, cameraBuffer, groupBuffer);
        textureInitPassKernel               = configuration.createKernel("textureInitPassRT", bsdfBuffer, isectBuffer, texBuffer);
        setupBSDFRaytraceKernel             = configuration.createKernel("SetupBSDFRaytrace", isectBuffer, raysBuffer, bsdfBuffer, mesh.clMaterials());
        updateToTextureColorRTKernel        = configuration.createKernel("updateToTextureColorRT", bsdfBuffer, texBuffer);
    }
    
    public void setEnvMapInKernel()
    {
        backgroundShadeKernel.putArg(4, envmap.getRgbCL());
    }
    
    @Override
    public void updateCamera(){
        CCamera cam = cameraModel.getCameraStruct();
        cam.setDimension(new CPoint2(getWidth(), getHeight()));
        cameraBuffer.setCL(cam);
        
        
    }
  
    @Override
    public void start()
    {     
       raytraceThread.startExecution(()-> {
            //execute pause             
            loop();
            raytraceThread.pauseExecution();       
        });      
    }
    
    private void loop()
    {        
        if(cameraModel.isSynched(cameraBuffer.get(0)))
            raytraceThread.chill();       
        updateCamera();
        configuration.execute1DKernel(initCameraRaysKernel, globalWorkSize, localWorkSize);
        configuration.execute1DKernel(intersectPrimitivesKernel, globalWorkSize, localWorkSize);
        configuration.execute1DKernel(setupBSDFRaytraceKernel, globalWorkSize, localWorkSize);
        
        //pass texture
        configuration.execute1DKernel(textureInitPassKernel, globalWorkSize, localWorkSize);
        texApplyPass.process();
        configuration.execute1DKernel(updateToTextureColorRTKernel, globalWorkSize, localWorkSize);
        
        configuration.execute1DKernel(backgroundShadeKernel, globalWorkSize, localWorkSize); 
        configuration.execute1DKernel(fastShadeKernel, globalWorkSize, localWorkSize);
        configuration.execute1DKernel(updateGroupbufferShadeImageKernel, globalWorkSize, localWorkSize);
        outputImage();
        configuration.finish();
      
        
        
        //raytraceThread.chill();
    }
    
    @Override
    public void outputImage() {      
        //transfer data from opencl to cpu
        imageBuffer.transferFromDevice();
        groupBuffer.transferFromDevice();
        //write to bitmap and overlay
        raytraceBitmap.writeColor((int[]) imageBuffer.getBufferArray(), 0, 0, width, height);
        overlay.copyToArray((int[])groupBuffer.getBufferArray());
        //image fill
        Platform.runLater(()-> display.imageFill(RAYTRACE_IMAGE.name(), raytraceBitmap));
        
    }
    
    public boolean isCoordinateAnInstance(double x, double y)
    {
        return overlay.isInstance(x, y);
    }
    
    public int getInstanceValue(double x, double y)
    {
        return overlay.get(x, y);
    }
    
    public Overlay getOverlay()
    {
        return overlay;
    }
    
    public int getWidth()
    {
        return width;
    }
    
    public int getHeight()
    {
        return height;
    }
    
    @Override
    public void pause() {
        raytraceThread.pauseExecution();
    }

    @Override
    public void stop() {
        raytraceThread.stopExecution();
    }
   
    @Override
    public boolean isPaused() {
        return raytraceThread.isPaused();
    }

    @Override
    public boolean isRunning() {
        return !raytraceThread.isPaused();
    }

    @Override
    public void resume() {
        raytraceThread.resumeExecution();
    }
    
    @Override
    public boolean isStopped() {
        return raytraceThread.isTerminated();
    }
    
    public void reposition(CBound box)
    {
        OrientationModel<CPoint3, CVector3, CRay, CBound> orientation = new OrientationModel(CPoint3.class, CVector3.class);
        orientation.repositionLocation(cameraModel, box);     
    }
    
    
    public CMesh getMesh()
    {
        return mesh;
    }
    
    public CNormalBVH getBVH()
    {
        return bvh;
    }
    
    @Override
    public CCameraModel getCameraModel()
    {
        return cameraModel;
    }

    @Override
    public void setAPI(TracerAPI api) {
        this.api = api;
        init(api.getConfigurationCL(), api.getBlendDisplayRT());
    }
    
    private void init(OpenCLConfiguration platform, BlendDisplay display)
    {
        this.configuration = platform;
        this.display = display;
        this.envmap = api.getEnvironmentalMapCL();
        initBuffers();
        initKernels();        
    }

    @Override
    public void set(CMesh mesh, CNormalBVH bvhBuild) {
        this.mesh = mesh;
        this.bvh = bvhBuild;        
        this.priorityBound = bvhBuild.getBound();
    }
    
    @Override
    public CBound getBound(){
        return bvh.getBound();
    } 

    @Override
    public void setGlobalSize(int globalSize) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void setLocalSize(int localSize) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void execute() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void setCamera(CCamera cameraData) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
    
    @Override
    public CBound getPriorityBound()
    {
        return priorityBound;
    }
    
    
}
