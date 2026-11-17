package app.buddy.assistant;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.regex.Pattern;

/**
 * 020: the "Bypass" approvals mode (nlachica fork). In a Bypass chat a card the caller opted in is skipped and noted
 * (BuddyService.askConfirm); what is about secrets or money keeps its card. A fork file, so Guard (upstream's) stays
 * as it is.
 */
final class Bypass {
    /** Session.approvals for this mode. */
    static final String MODE = "bypass";

    private Bypass() {}

    /** Is a card skipped: the caller opted in, the chat is in Bypass, and it isn't a notification rule's run. */
    static boolean skips(boolean optIn, String approvals, boolean ruleRun) {
        return optIn && MODE.equals(approvals) && !ruleRun;
    }

    /** Is the calling chat in Bypass (and not in a rule's run)? */
    static boolean on(android.content.Context c, String chat) {
        return chat != null && skips(true, ChatHub.get(c).approvalsFor(chat), Triggers.ruleRun(chat));
    }

    // a word's start and end, as Guard.routineText reads words; digits count so "2fa" isn't found inside "a2fa"
    private static final String B = "(?<![\\p{L}\\p{M}\\p{N}])";
    private static final String E = "(?![\\p{L}\\p{M}\\p{N}])";

    private static Pattern words(String list) {
        return Pattern.compile("(?iu)" + B + "(?:" + list.replace(" ", "[\\s_-]+") + ")" + E);
    }

    private static final Pattern SECRET = words("passwords?|passcodes?|passphrases?|pin code|otp|2fa|mfa|one[\\s_-]?time code"
            + "|(?:verification|security|login|auth|authentication) code|tokens?|secrets?|credentials?"
            + "|(?:api|private|secret|access|ssh) keys?|key files?|seed phrase|recovery phrase");
    /** "PIN" in capitals only: "Pin this chat" is no secret. */
    private static final Pattern PIN = Pattern.compile(B + "PIN" + E);
    /** Credential files and key extensions, in raw text (a shell command names a file, not "private key"). */
    private static final Pattern FILES = Pattern.compile("(?i)id_(?:rsa|dsa|ecdsa|ed25519)|\\.ssh(?![\\p{L}\\p{N}])"
            + "|\\.(?:env|netrc|pgpass|git-credentials|npmrc|pypirc)(?![\\p{L}\\p{N}_-])"
            + "|\\.(?:pem|key|p12|pfx|jks|keystore|kdbx|gpg)(?![\\p{L}\\p{N}])");
    /** A one-time code in a draft about to be sent. */
    private static final Pattern CODE = Pattern.compile("\\s*\\d{4,8}\\s*");

    // English, then the pay/buy/order/cart/checkout/subscribe/transfer/donate words of Guard.RISKY and
    // BrowserTools.CHECKOUT, plus the words that finish a checkout (confirm, submit)
    private static final Pattern MONEY = words("pay|pays|paying|paid|payments?|payouts?|paypal|venmo|zelle|cash app"
            + "|buy|buys|buying|bought|purchase[sd]?|purchasing|checkout|check out|orders?|ordering|ordered"
            + "|add to (?:cart|basket|bag|trolley)|addtocart|cart|basket|subscribe|subscriptions?|donate|donations?"
            + "|transfers?|wire|refunds?|invoices?|bank|banking|iban|credit card|debit card|card number|cvv|cvc"
            + "|money|wallet|crypto|bitcoin|confirm|submit"
            + "|payer|paiement|acheter|commander|ajouter au panier|passer (?:la )?commande|caisse|panier|virement"
            + "|valider|confirmer|s'abonner|transférer"
            + "|bezahlen|zahlung|zahlungspflichtig|kaufen|bestellen|einkaufswagen|warenkorb|kasse|bestätigen"
            + "|abonnieren|überweisen"
            + "|pagar|pago|comprar|pedir|añadir al carrito|agregar al carrito|carrito|pedido|realizar pedido"
            + "|finalizar compra|tramitar pedido|suscribirse|transferir|confirmar"
            + "|pagare|pagamento|paga|paga ora|compra|acquista|acquista ora|ordina|aggiungi al carrello|carrello"
            + "|conferma|abbonati|iscriviti|trasferisci|procedi all'acquisto"
            + "|comprar agora|fazer pedido|adicionar ao carrinho|carrinho|assinar|inscrever-se"
            + "|betalen|kopen|afrekenen|winkelwagen|bevestigen|overmaken|abonneren"
            + "|zapłać|kup|kup teraz|zamów|kupuję|zamawiam|do kasy|koszyk\\p{L}*|potwierdź|przelej|subskrybuj"
            + "|оплатить|купить|заказать|оформить заказ|корзин\\p{L}*|подтвердить|перевести|подписаться"
            + "|сплатити|купити|замовити|оформити замовлення|кошик\\p{L}*|підтвердити|переказати|підписатися"
            + "|öde|ödeme|satın al|hemen al|sipariş ver|siparişi tamamla|sepet\\p{L}*|onayla|abone ol"
            + "|भुगतान करें|खरीदें|अभी खरीदें|ऑर्डर करें|कार्ट में जोड़ें|पुष्टि करें"
            + "|bayar|beli|pesan sekarang|buat pesanan|keranjang|konfirmasi|berlangganan"
            + "|دفع|ادفع|شراء|اشتر|تأكيد|السلة"
            + "|thanh toán|mua|mua ngay|mua hàng|đặt hàng|đặt mua|thêm vào giỏ|xác nhận|chuyển tiền|chuyển khoản");
    /** Chinese, Japanese and Korean have no spaces between words: found at any position. */
    private static final Pattern CJK = Pattern.compile("支払|購入|注文|カート|確定|送金|購読|결제|구매|주문|장바구니|송금|이체|구독"
            + "|支付|付款|购买|下单|提交订单|购物车|确认|转账|订阅");
    private static final Pattern CURRENCY = Pattern.compile("[€£¥₹]|\\$\\d");

    /** Does any of these texts name a secret (password, code, key, token, key file) or money (paying, buying, orders)? */
    static boolean secretOrMoney(String... parts) {
        if (parts == null) return false;
        for (String s : parts) {
            if (s == null || s.isEmpty()) continue;
            if (CODE.matcher(s).matches() || SECRET.matcher(s).find() || PIN.matcher(s).find() || FILES.matcher(s).find()
                    || MONEY.matcher(s).find() || CJK.matcher(s).find() || CURRENCY.matcher(s).find()) return true;
        }
        return false;
    }

    /**
     * Does a tap name money? True in a payment app, or when any short label of the node under the tap or of the
     * button it belongs to does (not only the first risky one that Guard.riskyLabel stops at: "Send" over a "Pay now"
     * description). The same bounded walk as Guard.riskyLabel; it only decides whether a card already showing may be
     * skipped.
     */
    static boolean tapMoney(boolean moneyApp, AccessibilityNodeInfo target, AccessibilityNodeInfo hit) {
        return moneyApp || labelsMoney(target) || labelsMoney(hit);
    }

    private static boolean labelsMoney(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 4; i++) {
            if (nodeMoney(cur)) return true;
            if (cur.isClickable()) {
                for (int c = 0; c < Math.min(cur.getChildCount(), 6); c++) {
                    AccessibilityNodeInfo ch = cur.getChild(c);
                    if (ch != null && nodeMoney(ch)) return true;
                }
                break;
            }
            cur = cur.getParent();
        }
        return false;
    }

    private static boolean nodeMoney(AccessibilityNodeInfo n) {
        for (CharSequence cs : new CharSequence[]{n.getText(), n.getContentDescription(), n.getTooltipText()}) {
            if (cs == null) continue;
            String s = cs.toString().trim();
            // a label as Guard.risky reads one: short, so a long text under the finger doesn't count
            if (s.isEmpty() || s.length() > 30 || s.split("\\s+").length > 4) continue;
            if (secretOrMoney(s)) return true;
        }
        String id = n.getViewIdResourceName();
        return id != null && secretOrMoney(id.substring(id.lastIndexOf('/') + 1));
    }
}
