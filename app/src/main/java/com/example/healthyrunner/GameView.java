package com.example.healthyrunner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

public class GameView extends SurfaceView implements Runnable {

    // ---------- Screen states ----------
    private static final int STATE_HOME = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_GAME_OVER = 2;

    // ---------- Simple helper classes ----------
    private static class Obstacle {
        float x, w, h;
        boolean isRock;
    }

    private static class Apple {
        float x, y;
    }

    // ---------- Thread and drawing ----------
    private final SurfaceHolder holder;
    private Thread gameThread;
    private volatile boolean running = false;
    private final Object lock = new Object();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();

    // ---------- Game data ----------
    private int state = STATE_HOME;
    private int score = 0;
    private String endMessage = "";

    private final String[] messages = {
            "Eat fruits\nevery day!",
            "Stay active and\nstay healthy!",
            "Choose healthy food\nfor a healthy life!",
            "An apple a day keeps\nthe doctor away!"
    };

    // ---------- Sizes (set from screen size) ----------
    private int w = 0, h = 0;
    private float groundY;
    private float playerX, playerY, playerW, playerH;
    private float velY = 0;
    private float gravity, jumpSpeed;
    private float baseSpeed, maxSpeed, speedStep, speed;
    private float appleR;
    private boolean onGround = true;

    // ---------- Timers and scrolling ----------
    private float spawnTimer = 1.2f;
    private float runTime = 0;
    private float cloudX = 0, hillX = 0, groundX = 0;

    // ---------- Lists ----------
    private final ArrayList<Obstacle> obstacles = new ArrayList<>();
    private final ArrayList<Apple> apples = new ArrayList<>();

    // ---------- Buttons ----------
    private final RectF playBtn = new RectF();
    private final RectF againBtn = new RectF();
    private final RectF homeBtn = new RectF();
    private final RectF jumpBtn = new RectF();

    private final RectF playerRect = new RectF();
    private final RectF tempRect = new RectF();

    public GameView(Context context) {
        super(context);
        holder = getHolder();
        paint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    // =====================================================
    //  THREAD CONTROL (called from MainActivity)
    // =====================================================
    public void resume() {
        if (running) return;
        running = true;
        gameThread = new Thread(this);
        gameThread.start();
    }

    public void pause() {
        running = false;
        if (gameThread != null) {
            try {
                gameThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gameThread = null;
        }
    }

    // Returns true if we were not on the home screen and went back to it
    public boolean goBackToHome() {
        synchronized (lock) {
            if (state != STATE_HOME) {
                state = STATE_HOME;
                return true;
            }
            return false;
        }
    }

    // =====================================================
    //  GAME LOOP
    // =====================================================
    @Override
    public void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = (now - last) / 1000000000f;
            last = now;
            if (dt > 0.05f) dt = 0.05f;   // avoid huge jumps after a lag

            if (!holder.getSurface().isValid() || getWidth() == 0 || getHeight() == 0) {
                sleepMs(16);
                continue;
            }

            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) {
                    synchronized (lock) {
                        ensureSizes();
                        update(dt);
                        draw(canvas);
                    }
                }
            } catch (Exception e) {
                // surface was destroyed while drawing; ignore and try next frame
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }

            long took = (System.nanoTime() - now) / 1000000L;
            long wait = 16 - took;
            if (wait > 0) sleepMs(wait);
        }
    }

    private void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =====================================================
    //  SETUP
    // =====================================================
    private void ensureSizes() {
        int cw = getWidth();
        int ch = getHeight();
        if (cw == w && ch == h) return;
        w = cw;
        h = ch;

        groundY = h * 0.78f;
        playerW = h * 0.065f;
        playerH = h * 0.10f;
        playerX = w * 0.18f;
        playerY = groundY - playerH;
        velY = 0;
        onGround = true;

        gravity = h * 2.6f;
        jumpSpeed = h * 1.05f;
        baseSpeed = w * 0.55f;
        maxSpeed = w * 1.0f;
        speedStep = w * 0.012f;
        speed = baseSpeed;
        appleR = h * 0.022f;

        float bw = w * 0.6f;
        float bh = h * 0.075f;
        playBtn.set(w / 2f - bw / 2, h * 0.72f, w / 2f + bw / 2, h * 0.72f + bh);
        againBtn.set(w / 2f - bw / 2, h * 0.56f, w / 2f + bw / 2, h * 0.56f + bh);
        homeBtn.set(w / 2f - bw / 2, h * 0.66f, w / 2f + bw / 2, h * 0.66f + bh);
        float jd = w * 0.26f;   // jump button diameter
        jumpBtn.set(w - jd - w * 0.05f, h - jd - h * 0.04f, w - w * 0.05f, h - h * 0.04f);
        obstacles.clear();
        apples.clear();
    }

    private void startGame() {
        score = 0;
        speed = baseSpeed;
        obstacles.clear();
        apples.clear();
        playerY = groundY - playerH;
        velY = 0;
        onGround = true;
        spawnTimer = 1.2f;
        state = STATE_PLAYING;
    }

    // =====================================================
    //  TOUCH INPUT
    // =====================================================
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getActionIndex();
            float tx = event.getX(index);
            float ty = event.getY(index);
            synchronized (lock) {
                if (state == STATE_HOME) {
                    if (playBtn.contains(tx, ty)) {
                        startGame();
                    }
                } else if (state == STATE_PLAYING) {
                    // only the JUMP button makes the player jump
                    float dx = tx - jumpBtn.centerX();
                    float dy = ty - jumpBtn.centerY();
                    float reach = jumpBtn.width() / 2f * 1.3f;   // slightly bigger touch area
                    if (dx * dx + dy * dy <= reach * reach) {
                        if (onGround) {          // no jumping in mid-air
                            velY = -jumpSpeed;
                            onGround = false;
                        }
                    }
                } else if (state == STATE_GAME_OVER) {
                    if (againBtn.contains(tx, ty)) {
                        startGame();
                    } else if (homeBtn.contains(tx, ty)) {
                        state = STATE_HOME;
                    }
                }
            }
        }
        return true;
    }

    // =====================================================
    //  UPDATE
    // =====================================================
    private void update(float dt) {
        // clouds always drift slowly (home and game over screens too)
        if (state != STATE_PLAYING) {
            cloudX += w * 0.03f * dt;
            return;
        }

        runTime += dt;

        // speed grows slowly with score
        speed = baseSpeed + score * speedStep;
        if (speed > maxSpeed) speed = maxSpeed;

        // scrolling background
        cloudX += speed * 0.10f * dt;
        hillX += speed * 0.30f * dt;
        groundX += speed * dt;

        // jump physics
        velY += gravity * dt;
        playerY += velY * dt;
        if (playerY >= groundY - playerH) {
            playerY = groundY - playerH;
            velY = 0;
            onGround = true;
        }

        // spawn obstacles
        spawnTimer -= dt;
        if (spawnTimer <= 0) {
            spawnTimer = 1.5f + random.nextFloat() * 0.9f;
            spawnObstacleAndApples();
        }

        // move obstacles and remove those off the screen
        Iterator<Obstacle> oi = obstacles.iterator();
        while (oi.hasNext()) {
            Obstacle o = oi.next();
            o.x -= speed * dt;
            if (o.x + o.w < 0) oi.remove();
        }

        // move apples
        Iterator<Apple> ai = apples.iterator();
        while (ai.hasNext()) {
            Apple a = ai.next();
            a.x -= speed * dt;
            if (a.x + appleR < 0) ai.remove();
        }

        // player hitbox (a bit smaller than the picture so it feels fair)
        float padX = playerW * 0.15f;
        float padY = playerH * 0.10f;
        playerRect.set(playerX + padX, playerY + padY,
                playerX + playerW - padX, playerY + playerH - padY);

        // collect apples
        ai = apples.iterator();
        while (ai.hasNext()) {
            Apple a = ai.next();
            if (circleHitsRect(a.x, a.y, appleR, playerRect)) {
                score++;
                ai.remove();
            }
        }

        // collision with obstacles -> game over
        for (Obstacle o : obstacles) {
            float shrink = o.w * 0.10f;
            tempRect.set(o.x + shrink, groundY - o.h + shrink, o.x + o.w - shrink, groundY);
            if (RectF.intersects(playerRect, tempRect)) {
                endMessage = messages[random.nextInt(messages.length)];
                state = STATE_GAME_OVER;
                return;
            }
        }
    }

    private void spawnObstacleAndApples() {
        Obstacle o = new Obstacle();
        o.isRock = random.nextBoolean();
        o.w = h * 0.055f;
        o.h = h * (0.055f + random.nextFloat() * 0.02f);
        o.x = w + o.w;
        obstacles.add(o);

        // an apple between this obstacle and the next one (spawnTimer is the next gap)
        Apple mid = new Apple();
        mid.x = o.x + spawnTimer * speed * 0.5f;
        if (random.nextBoolean()) {
            mid.y = groundY - h * 0.05f;      // low apple, just run through it
        } else {
            mid.y = groundY - h * 0.17f;      // high apple, needs a jump
        }
        apples.add(mid);

        // sometimes a bonus apple floating above the obstacle
        if (random.nextInt(2) == 0) {
            Apple top = new Apple();
            top.x = o.x + o.w / 2f;
            top.y = groundY - h * 0.19f;
            apples.add(top);
        }
    }

    private boolean circleHitsRect(float cx, float cy, float r, RectF rect) {
        float nearestX = Math.max(rect.left, Math.min(cx, rect.right));
        float nearestY = Math.max(rect.top, Math.min(cy, rect.bottom));
        float dx = cx - nearestX;
        float dy = cy - nearestY;
        return (dx * dx + dy * dy) <= r * r;
    }

    // =====================================================
    //  DRAWING
    // =====================================================
    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        if (state == STATE_HOME) {
            drawHome(canvas);
        } else {
            drawWorld(canvas);
            if (state == STATE_PLAYING) {
                drawScore(canvas);
                drawJumpButton(canvas);
            } else {
                drawGameOver(canvas);
            }
        }
    }

    // ---------- Home screen ----------
    private void drawHome(Canvas c) {
        c.drawColor(Color.rgb(46, 125, 50));

        // soft decorative circles
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(60, 255, 255, 255));
        c.drawCircle(w * 0.1f, h * 0.12f, w * 0.18f, paint);
        c.drawCircle(w * 0.95f, h * 0.30f, w * 0.22f, paint);
        c.drawCircle(w * 0.15f, h * 0.88f, w * 0.25f, paint);

        drawCloud(c, ((cloudX) % (w * 1.4f)) - w * 0.2f, h * 0.06f, w * 0.05f);

        // title
        drawCenteredText(c, "HEALTHY", w / 2f, h * 0.16f, w * 0.15f, Color.WHITE, true);
        drawCenteredText(c, "RUNNER", w / 2f, h * 0.16f + w * 0.16f, w * 0.15f,
                Color.rgb(255, 235, 59), true);
        drawCenteredText(c, "Eat Healthy, Stay Healthy!", w / 2f, h * 0.30f, w * 0.058f,
                Color.WHITE, false);

        // big apple
        drawApple(c, w / 2f, h * 0.38f, w * 0.09f);

        // instruction card
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(90, 0, 0, 0));
        RectF card = new RectF(w * 0.10f, h * 0.45f, w * 0.90f, h * 0.67f);
        c.drawRoundRect(card, w * 0.04f, w * 0.04f, paint);

        drawCenteredText(c, "HOW TO PLAY", w / 2f, h * 0.49f, w * 0.05f,
                Color.rgb(255, 235, 59), false);
        drawCenteredText(c, "Press the JUMP button", w / 2f, h * 0.54f, w * 0.045f,
                Color.WHITE, false);
        drawCenteredText(c, "Collect apples: +1 score", w / 2f, h * 0.58f, w * 0.045f,
                Color.WHITE, false);
        drawCenteredText(c, "Avoid rocks and crates!", w / 2f, h * 0.62f, w * 0.045f,
                Color.WHITE, false);

        drawButton(c, playBtn, "PLAY GAME", Color.rgb(255, 152, 0));
    }

    // ---------- Gameplay world ----------
    private void drawWorld(Canvas c) {
        // sky
        c.drawColor(Color.rgb(135, 206, 250));

        // sun
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(255, 235, 59));
        c.drawCircle(w * 0.82f, h * 0.10f, w * 0.09f, paint);

        // clouds
        float span = w * 1.5f;
        for (int i = 0; i < 3; i++) {
            float cx = (((i * w * 0.5f) - cloudX) % span + span) % span - w * 0.25f;
            float cy = h * (0.15f + 0.07f * i);
            drawCloud(c, cx, cy, w * 0.045f);
        }

        // hills
        float hillSpacing = w * 0.6f;
        float off = hillX % hillSpacing;
        paint.setColor(Color.rgb(102, 187, 106));
        for (int i = 0; i < 4; i++) {
            float cx = i * hillSpacing - off;
            c.drawOval(new RectF(cx - hillSpacing * 0.5f, groundY - h * 0.10f,
                    cx + hillSpacing * 0.5f, groundY + h * 0.10f), paint);
        }

        // ground: grass on top, dirt below
        paint.setColor(Color.rgb(67, 160, 71));
        c.drawRect(0, groundY, w, h, paint);
        paint.setColor(Color.rgb(46, 125, 50));
        c.drawRect(0, groundY, w, groundY + h * 0.012f, paint);
        paint.setColor(Color.rgb(141, 110, 99));
        c.drawRect(0, groundY + h * 0.05f, w, h, paint);

        // moving dashes on the dirt so you can see the speed
        float dashSpacing = w * 0.2f;
        float dashOff = groundX % dashSpacing;
        paint.setColor(Color.rgb(109, 76, 65));
        for (int i = -1; i < 7; i++) {
            float dx = i * dashSpacing - dashOff;
            c.drawRect(dx, groundY + h * 0.09f, dx + w * 0.06f, groundY + h * 0.098f, paint);
            c.drawRect(dx + w * 0.1f, groundY + h * 0.14f, dx + w * 0.15f,
                    groundY + h * 0.148f, paint);
        }

        // apples
        for (Apple a : apples) {
            drawApple(c, a.x, a.y, appleR);
        }

        // obstacles
        for (Obstacle o : obstacles) {
            drawObstacle(c, o);
        }

        // player
        drawPlayer(c);
    }

    private void drawScore(Canvas c) {
        drawApple(c, w * 0.08f, h * 0.06f, w * 0.03f);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(w * 0.07f);
        paint.setColor(Color.argb(120, 0, 0, 0));
        c.drawText("Score: " + score, w * 0.14f + 3, h * 0.06f + w * 0.025f + 3, paint);
        paint.setColor(Color.WHITE);
        c.drawText("Score: " + score, w * 0.14f, h * 0.06f + w * 0.025f, paint);
    }
    private void drawJumpButton(Canvas c) {
        float cx = jumpBtn.centerX();
        float cy = jumpBtn.centerY();
        float r = jumpBtn.width() / 2f;

        paint.setStyle(Paint.Style.FILL);
        // shadow
        paint.setColor(Color.argb(80, 0, 0, 0));
        c.drawCircle(cx, cy + h * 0.006f, r, paint);
        // button
        paint.setColor(Color.argb(220, 255, 152, 0));
        c.drawCircle(cx, cy, r, paint);
        // white ring
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(w * 0.012f);
        paint.setColor(Color.WHITE);
        c.drawCircle(cx, cy, r * 0.92f, paint);
        paint.setStyle(Paint.Style.FILL);

        // up arrow
        paint.setColor(Color.WHITE);
        android.graphics.Path arrow = new android.graphics.Path();
        arrow.moveTo(cx, cy - r * 0.55f);
        arrow.lineTo(cx + r * 0.4f, cy - r * 0.05f);
        arrow.lineTo(cx - r * 0.4f, cy - r * 0.05f);
        arrow.close();
        c.drawPath(arrow, paint);

        drawCenteredText(c, "JUMP", cx, cy + r * 0.5f, w * 0.055f, Color.WHITE, false);
    }

    // ---------- Game over screen ----------
    private void drawGameOver(Canvas c) {
        // dim the world behind
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(150, 0, 0, 0));
        c.drawRect(0, 0, w, h, paint);

        // panel
        paint.setColor(Color.rgb(46, 125, 50));
        RectF panel = new RectF(w * 0.08f, h * 0.18f, w * 0.92f, h * 0.78f);
        c.drawRoundRect(panel, w * 0.05f, w * 0.05f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(w * 0.012f);
        paint.setColor(Color.WHITE);
        c.drawRoundRect(panel, w * 0.05f, w * 0.05f, paint);
        paint.setStyle(Paint.Style.FILL);

        drawCenteredText(c, "GAME OVER", w / 2f, h * 0.25f, w * 0.12f,
                Color.rgb(255, 235, 59), true);
        drawCenteredText(c, "Final Score: " + score, w / 2f, h * 0.32f, w * 0.075f,
                Color.WHITE, false);

        // healthy message (two lines separated by \n)
        String[] lines = endMessage.split("\n");
        float ty = h * 0.40f;
        for (String line : lines) {
            drawCenteredText(c, line, w / 2f, ty, w * 0.055f, Color.WHITE, false);
            ty += w * 0.075f;
        }

        drawButton(c, againBtn, "PLAY AGAIN", Color.rgb(255, 152, 0));
        drawButton(c, homeBtn, "BACK TO HOME", Color.rgb(33, 150, 243));
    }

    // ---------- Small drawing helpers ----------
    private void drawCenteredText(Canvas c, String text, float cx, float y, float size,
                                  int color, boolean shadow) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(size);
        if (shadow) {
            paint.setColor(Color.argb(140, 0, 0, 0));
            c.drawText(text, cx + size * 0.04f, y + size * 0.04f, paint);
        }
        paint.setColor(color);
        c.drawText(text, cx, y, paint);
    }

    private void drawButton(Canvas c, RectF r, String label, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(90, 0, 0, 0));
        c.drawRoundRect(new RectF(r.left, r.top + h * 0.008f, r.right, r.bottom + h * 0.008f),
                r.height() / 2f, r.height() / 2f, paint);
        paint.setColor(color);
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paint);
        drawCenteredText(c, label, r.centerX(), r.centerY() + w * 0.02f, w * 0.06f,
                Color.WHITE, false);
    }

    private void drawCloud(Canvas c, float x, float y, float r) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        c.drawCircle(x, y, r, paint);
        c.drawCircle(x + r * 1.1f, y - r * 0.4f, r * 1.2f, paint);
        c.drawCircle(x + r * 2.3f, y, r, paint);
        c.drawRect(x, y, x + r * 2.3f, y + r, paint);
    }

    private void drawApple(Canvas c, float cx, float cy, float r) {
        paint.setStyle(Paint.Style.FILL);
        // body
        paint.setColor(Color.rgb(229, 57, 53));
        c.drawCircle(cx, cy, r, paint);
        // highlight
        paint.setColor(Color.argb(150, 255, 255, 255));
        c.drawCircle(cx - r * 0.4f, cy - r * 0.35f, r * 0.2f, paint);
        // stem
        paint.setColor(Color.rgb(93, 64, 55));
        c.drawRect(cx - r * 0.08f, cy - r * 1.35f, cx + r * 0.08f, cy - r * 0.7f, paint);
        // leaf
        paint.setColor(Color.rgb(67, 160, 71));
        c.drawOval(new RectF(cx + r * 0.05f, cy - r * 1.4f, cx + r * 0.8f, cy - r * 0.95f), paint);
    }

    private void drawObstacle(Canvas c, Obstacle o) {
        paint.setStyle(Paint.Style.FILL);
        float top = groundY - o.h;
        if (o.isRock) {
            paint.setColor(Color.rgb(117, 117, 117));
            c.drawRoundRect(new RectF(o.x, top, o.x + o.w, groundY + o.h * 0.05f),
                    o.w * 0.4f, o.w * 0.4f, paint);
            paint.setColor(Color.rgb(158, 158, 158));
            c.drawCircle(o.x + o.w * 0.35f, top + o.h * 0.3f, o.w * 0.12f, paint);
        } else {
            paint.setColor(Color.rgb(121, 85, 72));
            c.drawRect(o.x, top, o.x + o.w, groundY, paint);
            paint.setColor(Color.rgb(78, 52, 46));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(o.w * 0.08f);
            c.drawRect(o.x, top, o.x + o.w, groundY, paint);
            c.drawLine(o.x, top, o.x + o.w, groundY, paint);
            c.drawLine(o.x + o.w, top, o.x, groundY, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void drawPlayer(Canvas c) {
        float x = playerX;
        float y = playerY;
        float pw = playerW;
        float ph = playerH;

        // leg swing while running, legs apart while jumping
        float swing = onGround ? (float) Math.sin(runTime * 16f) * pw * 0.35f : pw * 0.3f;
        float hipY = y + ph * 0.70f;
        float footY = y + ph;
        float hipX = x + pw * 0.5f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(pw * 0.2f);
        paint.setColor(Color.rgb(38, 50, 56));
        c.drawLine(hipX, hipY, hipX + swing, footY, paint);
        c.drawLine(hipX, hipY, hipX - swing, footY, paint);

        // arms
        paint.setStrokeWidth(pw * 0.15f);
        paint.setColor(Color.rgb(255, 204, 128));
        float shoulderY = y + ph * 0.45f;
        c.drawLine(hipX, shoulderY, hipX - swing * 0.8f, shoulderY + ph * 0.18f, paint);
        c.drawLine(hipX, shoulderY, hipX + swing * 0.8f, shoulderY + ph * 0.18f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        // body
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(255, 112, 67));
        c.drawRoundRect(new RectF(x + pw * 0.15f, y + ph * 0.38f, x + pw * 0.85f, y + ph * 0.74f),
                pw * 0.2f, pw * 0.2f, paint);

        // head
        float headR = pw * 0.4f;
        float headX = x + pw * 0.5f;
        float headY = y + headR;
        paint.setColor(Color.rgb(255, 204, 128));
        c.drawCircle(headX, headY, headR, paint);

        // cap
        paint.setColor(Color.rgb(33, 150, 243));
        c.drawArc(new RectF(headX - headR, headY - headR, headX + headR, headY + headR),
                180, 180, true, paint);
        c.drawRect(headX, headY - headR * 0.2f, headX + headR * 1.4f, headY, paint);

        // eye and smile
        paint.setColor(Color.WHITE);
        c.drawCircle(headX + headR * 0.4f, headY + headR * 0.1f, headR * 0.22f, paint);
        paint.setColor(Color.BLACK);
        c.drawCircle(headX + headR * 0.48f, headY + headR * 0.1f, headR * 0.1f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(pw * 0.05f);
        c.drawArc(new RectF(headX - headR * 0.1f, headY + headR * 0.15f,
                headX + headR * 0.6f, headY + headR * 0.6f), 20, 140, false, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}