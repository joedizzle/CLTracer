__kernel void InitCameraRayDataJitter(
    global CameraStruct* camera,
    global Ray* rays,
    global State* state)
{
    //global id and pixel making
    int id= get_global_id( 0 );      

    //pixel value
    float2 pixel = getPixel(id, camera->dimension.x, camera->dimension.y);

    //camera matrix, m = world_to_view, mInv = view_to_world
    transform camera_matrix = camera_transform(camera->position, camera->lookat, camera->up);

    //get global ray
    global Ray* r = rays + id;

    //distance to ndc and then aspect ratio
    float d = 1.0f/tan(radians((*camera).fov)/2.0f);
    float a = camera->dimension.x/camera->dimension.y;
    
    //seeds for each thread
    int2 seed = generate_seed(state);
    
    //generate random number (0 to 1)
    float2 sample                = random_float2(&seed);
    float jitter1                = 1.f/camera->dimension.x * (2 * sample.x - 1.f);
    float jitter2                = 1.f/camera->dimension.y * (2 * sample.y - 1.f);

    //direction (px, py, pz, 0) and origin (0, 0, 0, 0)
    r->d = normalize((float4)(a * (2.f * pixel.x/camera->dimension.x - 1 + jitter1), -2.f * pixel.y/camera->dimension.y + 1 + jitter2, -d, 0));
    r->o = 0;  //will be important for global illumination, when we reuse the rays

    //transform to world space
    r->o = transform_point4(camera_matrix.mInv, r->o);
    r->d = transform_vector4(camera_matrix.mInv, r->d);

    //init ray
    initGlobalRay(r, r->o, r->d);    
}