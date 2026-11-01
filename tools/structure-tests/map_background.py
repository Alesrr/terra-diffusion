"""Existing seed-atlas web-map palette and hill shading, reused without algorithm changes.

The backdrop is coarse model geography, not sampled Minecraft surface blocks.
"""
from PIL import Image

def paint_continents(data,path,blocks=131072):
    ch=data['channels'];n=512;planes=[]
    for key in ('Elev','Temp','Precip'):
        grid=ch[key];img=Image.new('F',(len(grid[0]),len(grid)));img.putdata([v for row in grid for v in row])
        planes.append(img.resize((n,n),Image.Resampling.BICUBIC))
    e,t,p=[v.load() for v in planes];image=Image.new('RGB',(n,n));out=image.load();land=0
    # Interpolate fields before classifying colors: coastlines remain distinct rather than blurred color blobs.
    # This shows coarse geography, not invented fine terrain or exact finished Minecraft biomes.
    for z in range(n):
        for x in range(n):
            v=e[x,z];c=color(v,t[x,z],p[x,z])
            if v>=0:
                land+=1
                run=2*(blocks/n)*7.5
                dx=(e[min(n-1,x+1),z]-e[max(0,x-1),z])/run
                dz=(e[x,min(n-1,z+1)]-e[x,max(0,z-1)])/run
                shade=max(.6,min(1.25,1-.7*dx-.5*dz));c=tuple(min(255,int(a*shade)) for a in c)
            out[x,z]=c
    image.resize((1024,1024),Image.Resampling.LANCZOS).save(path)
    return dict(continentalLandShare=round(land/(n*n),3),continentalMaxElevation=round(max(max(row) for row in ch['Elev'])))

def color(e,t,p):
    if e<0:
        f=min(1,-e/5000);return (int(44-22*f),int(112-53*f),int(158-52*f))
    # Fixed climate palette and altitude shading across every seed; elevation is in model metres.
    if t<0:c=(208,222,224)
    elif t<5:c=(105,145,133)
    elif p<250:c=(202,178,112)
    elif p<650:c=(160,170,91)
    elif p<1600:c=(88,144,83)
    else:c=(47,114,75)
    f=min(.55,max(0,e-1800)/5500);return tuple(int(v*(1-f)+210*f) for v in c)
