package app.buddy.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.accessibility.AccessibilityNodeInfo;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 020: Bypass skips only an opted-in card in a Bypass chat, and never what is about secrets or money. */
public class BypassTest {
    @Test
    public void secretsAndMoneyAreRecognised() {
        String[] yes = {"Pay $20 to Anna", "Buy now", "Place order", "Checkout", "Add to cart", "Subscribe to Premium",
                "Transfer money to savings", "Send €5", "Tap “Confirm” in Shop", "Submit", "Type the password",
                "Enter the one-time code", "Read the API key", "gh auth token", "cat /sdcard/id_rsa",
                "cat /sdcard/Download/.env", "ls /sdcard/.ssh/", "cp /sdcard/release.jks /sdcard/x", "cat ~/.netrc",
                "482913", "credit card number", "https://www.amazon.com/gp/buy/spc/handlers",
                "https://shop.example/checkout/", "Jetzt kaufen", "Bezahlen", "Bestätigen", "注文する", "결제하기",
                "thanh toán", "api_key", "Enter your PIN", "Use the pin code"};
        for (String s : yes) assertTrue(s, Bypass.secretOrMoney(s));
        String[] no = {"Send the message to Anna", "Delete the old screenshots", "Install the app", "Turn on Bluetooth",
                "Push the code to GitHub", "Open VS Code", "Tap “Next” in Display settings", "pm list packages",
                "echo $HOME", "keyboard", "codex", "payload.json", "confirmation sent", "Pin this chat",
                "https://example.com/article", "", "paylaş", "spinner"};
        for (String s : no) assertFalse(s, Bypass.secretOrMoney(s));
        assertFalse(Bypass.secretOrMoney((String) null));
        assertFalse(Bypass.secretOrMoney((String[]) null));
        assertTrue(Bypass.secretOrMoney("hello", "Pay now"));
    }

    /** The money labels and ids Guard's risky words and BrowserTools' ids card (send/delete words excluded). */
    @Test
    public void everyMoneyLabelOfTheAskRulesIsMoney() {
        String[] money = {"Pedir", "Dodaj do koszyka", "Do koszyka", "addtocart", "add-to-cart", "add_to_cart", "Add to basket",
                "Pay now", "Place order", "Passer la commande", "In den Warenkorb", "Zahlungspflichtig bestellen", "Toevoegen aan winkelwagen",
                "Добавить в корзину", "Додати до кошика", "Sepete ekle", "Tambah ke keranjang", "カートに入れる", "加入购物车",
                "Inscrever-se", "Iscriviti", "Adicionar ao carrinho", "Añadir al carrito", "Aggiungi al carrello"};
        for (String s : money) assertTrue(s, Bypass.secretOrMoney(s));
        assertFalse(Bypass.secretOrMoney("Kosztorys"));
    }

    @Test
    public void noCardFrameDecidesByItself() throws Exception {
        String[] names = {"tools_card_tap", "tools_card_tap_in", "tools_card_tap_this", "tools_card_tap_this_in",
                "tools_card_enter", "tools_card_enter_in", "tools_card_detail", "tools_card_quote",
                "tools_card_no_reviewer", "tools_card_reviewer_block", "tools_card_reviewer_ask",
                "tools_card_location", "tools_card_shell", "browser_card_tap", "browser_card_enter",
                "browser_card_dialog"};
        File[] dirs = new File("src/main/res").listFiles((d, n) -> n.startsWith("values"));
        int seen = 0;
        for (File d : dirs) {
            File f = new File(d, "strings.xml");
            if (!f.exists()) continue;
            String xml = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String name : names) {
                Matcher m = Pattern.compile("<string name=\"" + name + "\"[^>]*>(.*?)</string>", Pattern.DOTALL).matcher(xml);
                if (!m.find()) continue;
                seen++;
                String frame = m.group(1).replaceAll("%\\d\\$s", "");
                assertFalse(f + " " + name + ": " + frame, Bypass.secretOrMoney(frame));
            }
        }
        assertTrue(seen >= names.length);
    }

    @Test
    public void onlyAnOptedInCardInABypassChatSkips() {
        assertTrue(Bypass.skips(true, "bypass", false));
        assertFalse(Bypass.skips(false, "bypass", false));
        assertFalse(Bypass.skips(true, "bypass", true));
        for (String x : new String[]{"routine", "strict", "", null}) assertFalse(x, Bypass.skips(true, x, false));
    }

    /** A node with fixed labels, for the tap walk. */
    private static class Node extends AccessibilityNodeInfo {
        String text, desc, tip, id;
        boolean clickable;
        Node parent;
        final List<Node> kids = new ArrayList<>();

        Node(String text, String desc, String tip, String id, boolean clickable) {
            this.text = text; this.desc = desc; this.tip = tip; this.id = id; this.clickable = clickable;
        }

        Node add(Node k) { k.parent = this; kids.add(k); return this; }
        @Override public CharSequence getText() { return text; }
        @Override public CharSequence getContentDescription() { return desc; }
        @Override public CharSequence getTooltipText() { return tip; }
        @Override public String getViewIdResourceName() { return id; }
        @Override public boolean isClickable() { return clickable; }
        @Override public int getChildCount() { return kids.size(); }
        @Override public AccessibilityNodeInfo getChild(int i) { return kids.get(i); }
        @Override public AccessibilityNodeInfo getParent() { return parent; }
    }

    private static Node button(String text, String desc, String tip, String id) {
        return new Node(text, desc, tip, id, true);
    }

    @Test
    public void everyShortLabelOfATapReachesTheDecision() {
        assertTrue(Bypass.tapMoney(false, button("Continue", "Pay now", null, null), null));
        assertTrue(Bypass.tapMoney(false, button("Send", "Pay now", null, null), null));
        assertTrue(Bypass.tapMoney(false, button("Send", null, "Pay", null), null));
        assertTrue(Bypass.tapMoney(false, button("Send", null, null, "com.shop:id/pay_button"), null));
        assertTrue(Bypass.tapMoney(false, button(null, null, null, null).add(new Node(null, "Buy", null, null, false)), null));
        assertTrue(Bypass.tapMoney(false, button("Continue", null, null, null), button(null, "Pay", null, null)));
        assertTrue(Bypass.tapMoney(true, button("Continue", null, null, null), null));
        assertFalse(Bypass.tapMoney(false, button("Continue", null, null, null), null));
        assertFalse(Bypass.tapMoney(false, button("Next", null, null, "com.x:id/next"), button("Send", null, null, null)));
        assertFalse(Bypass.tapMoney(false, button("Send", "Free shipping on orders over $25", null, null), null));
        assertFalse(Bypass.tapMoney(false, null, null));
    }

    @Test
    public void aDraftIsJudgedWhole() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 301; i++) sb.append('x');
        String draft = sb + " password: x";
        assertTrue(Bypass.secretOrMoney(draft));
        assertFalse(Bypass.secretOrMoney(ChatHub.clip(draft, 300)));
        assertTrue(Bypass.secretOrMoney("password: example" + "!"));
        assertFalse(Bypass.secretOrMoney("!"));
        assertEquals(true, Bypass.secretOrMoney(sb + " Pay $20"));
    }

    private static org.json.JSONObject j(String json) throws Exception {
        return new org.json.JSONObject(json);
    }

    @Test
    public void theBrowsersMoneyContextCountsBeforePresentation() throws Exception {
        assertTrue(BrowserTools.money(j("{submitLabel:'Pay'}")));
        assertTrue(BrowserTools.money(j("{ok:true,label:'Message',multiline:true,search:false,submitLabel:'Send',payForm:false,form:'/checkout'}")));
        assertTrue(BrowserTools.money(j("{label:'→',title:'Pay now'}")));
        assertTrue(BrowserTools.money(j("{label:'',ids:'add-to-cart-button'}")));
        assertTrue(BrowserTools.money(j("{label:'Continue',payForm:true}")));
        assertTrue(BrowserTools.money(j("{label:'Continue',form:'/checkout/step2'}")));
        // checkout evidence riskyTap reads (CHECKOUT) keeps its card too, as a form name or in the address
        assertTrue(BrowserTools.money(j("{label:'Continue',submits:true,form:'/billing'}")));
        assertTrue(BrowserTools.money(j("{label:'Continue',submits:true,form:'checkoutForm'}")));
        assertTrue(BrowserTools.moneyUrl("https://shop.example/checkout-step2"));
        assertTrue(BrowserTools.moneyUrl("https://shop.example/billing/address"));
        assertFalse(BrowserTools.moneyUrl("https://shop.example/note"));
        assertFalse(BrowserTools.money(j("{label:'Continue',submits:true,form:'/note'}")));
        assertFalse(BrowserTools.money(j("{label:'Send'}")));
        assertFalse(Bypass.secretOrMoney("https://mail.example/compose"));
        assertFalse(BrowserTools.money(j("{submitLabel:'Search'}")));
        assertFalse(BrowserTools.money(j("{ok:true,label:'Message',multiline:true,search:false,submitLabel:'Send',payForm:false,form:'/note'}")));
        assertFalse(BrowserTools.money(j("{label:'Delete',title:'Delete draft'}")));
    }

    @Test
    public void aBypassedTapDoesNotApproveAMoneyDialog() {
        assertTrue(BrowserTools.dialogAsks(true, true, "Pay $20?"));
        assertTrue(Bypass.secretOrMoney("Pay $20?")); // so it cards
        assertTrue(BrowserTools.dialogAsks(true, true, "Delete this item?"));
        assertFalse(Bypass.secretOrMoney("Delete this item?")); // so it is skipped and noted
        assertFalse(BrowserTools.dialogAsks(true, false, "Pay $20?")); // upstream, unchanged
        assertTrue(BrowserTools.dialogAsks(false, false, "Pay $20?"));
        assertFalse(BrowserTools.dialogAsks(false, false, "Hello"));
        // a money site keeps the card even when the dialog's own words are harmless
        assertTrue(BrowserTools.dialogAsks(false, true, "Send now?"));
        assertFalse(Bypass.secretOrMoney("Send now?"));
        assertTrue(Bypass.secretOrMoney("https://bank.example/checkout"));
        assertTrue(Guard.moneyApp(null, "bank.example"));
    }

    private static String asset(String name) throws Exception {
        return new String(Files.readAllBytes(new File("src/main/assets/" + name).toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void theBrowserScriptGivesEnterTheFormAndTheWholeDraft() throws Exception {
        String js = asset("browser.js");
        String focus = js.substring(js.indexOf("B.focus = function"), js.indexOf("B.choose = function"));
        assertTrue(focus.contains("payForm:") && focus.contains("form:") && focus.contains("submitLabel:"));
        String target = js.substring(js.indexOf("B.target = function"), js.indexOf("B.armClick"));
        assertTrue(target.contains("draft: B.draft("));
        String draft = js.substring(js.indexOf("B.draft = function"), js.indexOf("function payForm"));
        assertFalse(draft.contains("300"));
    }
}
