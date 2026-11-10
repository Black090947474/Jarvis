package de.jarvis.app.wecker;

import de.jarvis.app.R;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.view.TextureView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** Einfache Kamera-Vorschau (Rückkamera) mit Schnappschuss. Funktioniert auch über dem Sperrbildschirm. */
@SuppressWarnings("deprecation")
public class CameraView extends TextureView implements TextureView.SurfaceTextureListener {

    public interface Callback { void onPicture(Bitmap bitmap); }

    private Camera camera;
    private int orientation = 90;
    private boolean busy;

    public CameraView(Context c) {
        super(c);
        setSurfaceTextureListener(this);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = w * 4 / 3;
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
            int maxH = MeasureSpec.getSize(heightSpec);
            if (h > maxH) {
                h = maxH;
                w = h * 3 / 4;
            }
        }
        setMeasuredDimension(w, h);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { open(st); }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    @Override
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }

    private void open(SurfaceTexture st) {
        try {
            int id = 0;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break; }
            }
            Camera.getCameraInfo(id, info);
            orientation = info.orientation;
            camera = Camera.open(id);

            try {
                Camera.Parameters p = camera.getParameters();
                Camera.Size best = null;
                for (Camera.Size s : p.getSupportedPreviewSizes()) {
                    if (best == null || score(s) < score(best)) best = s;
                }
                if (best != null) p.setPreviewSize(best.width, best.height);
                p.setPreviewFormat(ImageFormat.NV21);
                List<String> modes = p.getSupportedFocusModes();
                if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                }
                camera.setParameters(p);
            } catch (Exception ignored) {}

            camera.setDisplayOrientation(orientation);
            camera.setPreviewTexture(st);
            camera.startPreview();
        } catch (Exception e) {
            release();
            Toast.makeText(getContext(), "Kamera konnte nicht geöffnet werden.", Toast.LENGTH_LONG).show();
        }
    }

    private static int score(Camera.Size s) {
        return Math.abs(s.width - 640) + Math.abs(s.height - 480);
    }

    public void capture(final Callback cb) {
        if (camera == null || busy) return;
        busy = true;
        camera.setOneShotPreviewCallback((data, cam) -> {
            busy = false;
            try {
                Camera.Size s = cam.getParameters().getPreviewSize();
                YuvImage y = new YuvImage(data, ImageFormat.NV21, s.width, s.height, null);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                y.compressToJpeg(new Rect(0, 0, s.width, s.height), 90, out);
                byte[] jpg = out.toByteArray();
                Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                if (orientation != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(orientation);
                    b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                }
                cb.onPicture(b);
            } catch (Exception e) {
                Toast.makeText(getContext(), "Foto fehlgeschlagen – nochmal versuchen.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void release() {
        if (camera != null) {
            try { camera.setPreviewCallback(null); } catch (Exception ignored) {}
            try { camera.stopPreview(); } catch (Exception ignored) {}
            try { camera.release(); } catch (Exception ignored) {}
            camera = null;
        }
        busy = false;
    }
}
