package ai.actiondoc;

import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

/** Where a page draws its images, so text pages OCR only their figures (web read.js imageRegions). */
final class ImageRegions {
    /** Unrotated page user space, clipped to the crop box; pixels are the image's own. */
    record Region(double x,double y,double width,double height,long pixels) {}

    static List<Region> find(PDPage page) throws IOException {
        var regions=new ArrayList<Region>();
        var box=page.getCropBox();var visible=new Rectangle2D.Double(box.getLowerLeftX(),box.getLowerLeftY(),box.getWidth(),box.getHeight());
        new PDFGraphicsStreamEngine(page) {
            @Override public void drawImage(PDImage image) {
                // An image is drawn as the unit square under the current transformation matrix.
                var bounds=getGraphicsState().getCurrentTransformationMatrix().createAffineTransform().createTransformedShape(new Rectangle2D.Double(0,0,1,1)).getBounds2D().createIntersection(visible);
                if(bounds.getWidth()>0 && bounds.getHeight()>0)regions.add(new Region(bounds.getX(),bounds.getY(),bounds.getWidth(),bounds.getHeight(),(long)image.getWidth()*image.getHeight()));
            }
            @Override public void appendRectangle(Point2D a,Point2D b,Point2D c,Point2D d){}
            @Override public void clip(int rule){}
            @Override public void moveTo(float x,float y){}
            @Override public void lineTo(float x,float y){}
            @Override public void curveTo(float x1,float y1,float x2,float y2,float x3,float y3){}
            @Override public Point2D getCurrentPoint(){return new Point2D.Float();}
            @Override public void closePath(){}
            @Override public void endPath(){}
            @Override public void strokePath(){}
            @Override public void fillPath(int rule){}
            @Override public void fillAndStrokePath(int rule){}
            @Override public void shadingFill(COSName name){}
        }.processPage(page);
        return regions;
    }

    /** Same page-to-pixel transform PDFRenderer applies: scale, /Rotate, then y flip at the crop box origin. */
    static BufferedImage crop(BufferedImage rendered,PDPage page,Region region,float scale) {
        var box=page.getCropBox();int rotation=page.getRotation();
        var transform=AffineTransform.getScaleInstance(scale,scale);
        switch(rotation){case 90->transform.translate(box.getHeight(),0);case 180->transform.translate(box.getWidth(),box.getHeight());case 270->transform.translate(0,box.getWidth());default->{}}
        transform.rotate(Math.toRadians(rotation));
        transform.translate(0,box.getHeight());transform.scale(1,-1);transform.translate(-box.getLowerLeftX(),-box.getLowerLeftY());
        var pixels=transform.createTransformedShape(new Rectangle2D.Double(region.x(),region.y(),region.width(),region.height())).getBounds2D();
        int left=Math.min(rendered.getWidth()-1,Math.max(0,(int)Math.floor(pixels.getMinX()))),top=Math.min(rendered.getHeight()-1,Math.max(0,(int)Math.floor(pixels.getMinY())));
        int right=Math.min(rendered.getWidth(),(int)Math.ceil(pixels.getMaxX())),bottom=Math.min(rendered.getHeight(),(int)Math.ceil(pixels.getMaxY()));
        return rendered.getSubimage(left,top,Math.max(1,right-left),Math.max(1,bottom-top));
    }
}
