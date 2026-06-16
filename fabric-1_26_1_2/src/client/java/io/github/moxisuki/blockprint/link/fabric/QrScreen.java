package io.github.moxisuki.blockprint.link.fabric;

import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import io.github.moxisuki.blockprint.link.bridge.ui.QrData;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class QrScreen extends Screen {
    private static final ItemStack ICON_IRON    = new ItemStack(Items.IRON_INGOT);
    private static final ItemStack ICON_GOLD    = new ItemStack(Items.GOLD_INGOT);
    private static final ItemStack ICON_DIAMOND = new ItemStack(Items.DIAMOND);
    private static final Identifier SP_LINK = Identifier.withDefaultNamespace("icon/link");
    private static final Identifier SP_NEWS = Identifier.withDefaultNamespace("icon/news");

    private int qrX, qrY, tokenX, tokenY, tokenW;
    private boolean hoverCopy;

    public QrScreen() {
        super(Component.translatable("blockprintlink.ui.title"));
        QrData.ensure();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
        if (e.key() == 256 || e.key() == BridgeConfig.hotkeyCode()) { onClose(); return true; }
        return super.keyPressed(e);
    }
    @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean afterDrag) {
        if (!afterDrag && hoverCopy && minecraft != null && e.button() == 0) {
            String url = QrData.connectUrl();
            if (url != null) {
                minecraft.keyboardHandler.setClipboard(url);
                if (minecraft.player != null) minecraft.player.sendSystemMessage(Component.translatable("blockprintlink.ui.copied"));
                return true;
            }
        }
        return super.mouseClicked(e, afterDrag);
    }
    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(null); }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        int px=(width-QrData.PANEL_W)/2,py=(height-QrData.PANEL_H)/2,bx=px+5,by=py+5,bw=QrData.PANEL_W-10,bh=QrData.PANEL_H-10;
        g.fill(0,0,width,height,QrData.C_BG);
        g.fill(px,py,px+QrData.PANEL_W,py+QrData.PANEL_H,QrData.C_BORDER_D);
        g.fill(px+4,py+4,px+QrData.PANEL_W-4,py+QrData.PANEL_H-4,QrData.C_BORDER_L);
        g.fill(bx,by,bx+bw,by+bh,QrData.C_PANEL);g.fill(bx,by,bx+bw,by+16,QrData.C_ACCENT);
        if(font!=null){g.blitSprite(RenderPipelines.GUI_TEXTURED,SP_LINK,bx+7,by+4,8,8);g.text(font,I18n.get("blockprintlink.ui.title"),bx+19,by+4,0xFFFFFFFF,true);String h=BridgeConfig.hotkeyName()+" / ESC";g.text(font,h,bx+bw-font.width(h)-6,by+4,0xFFAAAAAA,false);}
        qrX=px+(QrData.PANEL_W-QrData.QR_SIZE)/2;qrY=by+22;int[]qr=QrData.pixels();
        if(qr!=null){slotBorder(g,qrX-3,qrY-3,QrData.QR_SIZE+6,QrData.QR_SIZE+6);g.fill(qrX,qrY,qrX+QrData.QR_SIZE,qrY+QrData.QR_SIZE,0xFFFFFFFF);
        for(int y=0;y<QrData.qrH();y++){int r=-1;for(int x=0;x<=QrData.qrW();x++){boolean b=x<QrData.qrW()&&(qr[y*QrData.qrW()+x]&0x00FFFFFF)==0;if(b&&r<0)r=x;else if(!b&&r>=0){g.horizontalLine(qrX+r,qrX+x-1,qrY+y,0xFF000000);r=-1;}}}}
        if(font==null)return;int iy=qrY+QrData.QR_SIZE+16,ix=bx+14,rh=QrData.ROW_H;g.fill(bx+4,iy-4,bx+bw-4,iy+rh*3+6,0x18000000);
        g.fakeItem(ICON_IRON,ix,iy);g.fakeItem(ICON_GOLD,ix,iy+rh);g.fakeItem(ICON_DIAMOND,ix,iy+rh*2);
        g.text(font,QrData.ipText(),ix+18,iy+4,QrData.C_TEXT,false);g.text(font,QrData.portText(),ix+18,iy+rh+4,QrData.C_TEXT,false);
        String t=QrData.tokenText();tokenX=ix+18;tokenY=iy+rh*2+4;tokenW=font.width(t);
        hoverCopy=mx>=tokenX&&mx<=tokenX+tokenW&&my>=tokenY-1&&my<=tokenY+10;
        g.text(font,t,tokenX,tokenY,hoverCopy?0xFF00BCD4:QrData.C_TEXT,false);if(hoverCopy)g.text(font," ⚡",tokenX+tokenW,tokenY,0xFF00BCD4,false);
        g.text(font,QrData.keyText(),bx+bw/2-font.width(QrData.keyText())/2,iy+rh*3+4,QrData.C_TEXT_DIM,false);
        int fy=by+bh-14;g.horizontalLine(bx,bx+bw,fy,0xFF8B8B8B);g.fill(bx,fy+1,bx+bw,by+bh,QrData.C_ACCENT);
        g.blitSprite(RenderPipelines.GUI_TEXTURED,SP_NEWS,bx+7,fy+4,8,8);g.text(font,I18n.get("blockprintlink.ui.footer"),bx+19,fy+4,0xFFBBBBBB,false);
        String v=I18n.get("blockprintlink.ui.version",io.github.moxisuki.blockprint.link.bridge.ClientMeta.mcVersion());
        g.text(font,v,bx+bw-font.width(v)-6,fy+4,0xFF888888,false);
    }
    private static void slotBorder(GuiGraphicsExtractor g,int x,int y,int w,int h){g.fill(x,y,x+w,y+h,0xFF373737);g.horizontalLine(x,x+w-1,y,0xFFFFFFFF);g.verticalLine(x,y,y+h-1,0xFFFFFFFF);g.horizontalLine(x+1,x+w,y+h-1,0xFF8B8B8B);g.verticalLine(x+w-1,y+1,y+h,0xFF8B8B8B);}
}
