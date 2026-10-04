package com.vone.vmq;

import java.math.BigDecimal;

/**
 * 防误识别守卫,规则移植自 xytxg refactor/android-modernization 的 PaymentParser:
 * 拒绝退款/提现等非入账话术、异常长度文本与越界金额。
 * 只做守卫不做解析,金额仍由 getMoney 系列提取(需兼容店员通/商家积分/企业微信等格式)。
 */
public final class PaymentFilter {

    private static final String[] NON_PAYMENT_WORDS = {
            "退款", "退还", "失败", "提现", "转出", "支出", "待收款", "汇总", "合计"
    };
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");

    private PaymentFilter() {
    }

    public static boolean accept(String notificationText, String money) {
        if (notificationText == null || notificationText.length() > 8192) {
            return false;
        }
        for (String word : NON_PAYMENT_WORDS) {
            if (notificationText.contains(word)) {
                return false;
            }
        }
        try {
            BigDecimal amount = new BigDecimal(money);
            return amount.signum() > 0 && amount.compareTo(MAX_AMOUNT) <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
