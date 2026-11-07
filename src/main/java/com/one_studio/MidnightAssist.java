package com.one_studio;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * MidnightAssist - الفئة الرئيسية التي ت inicialize المود عند بدء اللعبة
 * تسجل الأوامر وتحمّل الإعدادات عند التشغيل
 */
public class MidnightAssist implements ModInitializer {

    // مثال واحد (Singleton) للفئة الرئيسية
    public static final MidnightAssist INSTANCE = new MidnightAssist();

    // أداة التسجيل (Logger) لإظهار الرسائل في وحدة التحكم والملفات
    private static final Logger LOGGER = LoggerFactory.getLogger("midnight-assist");

    // منشئ خاص لمنع إنشاء نسخ متعددة
    private MidnightAssist() {
    }

    /**
     * تهيئة المود عند بدء تشغيل Minecraft
     * تسجل الأوامر وتحمّل الإعدادات المحفوظة
     */
    @Override
    public void onInitialize() {
        // طباعة رسالة تأكيد تهيئة المود في السجلات
        LOGGER.info("Midnight Assist initialized!");

        // تحميل إعدادات المود من ملف config
        MidnightAssistConfig.INSTANCE.load();

        // تسجيل أوامر الدردشة الخاصة بالمود
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, commandSelection) -> {
            // أمر "midnight reload" - إعادة تحميل الإعدادات من الملف
            dispatcher.register(
                Commands.literal("midnight")
                    .then(Commands.literal("reload")
                        .executes(context -> {
                            // إعادة تحميل الإعدادات
                            MidnightAssistConfig.INSTANCE.load();

                            // إرسال رسالة تأكيد للاعب
                            MutableComponent prefix = Component.translatable("chat.midnight-assist.prefix");
                            MutableComponent message = Component.translatable("chat.midnight-assist.reloaded");
                            ((CommandSourceStack) context.getSource()).sendSuccess(
                                (Supplier<Component>) () -> prefix.copy().append(message), false
                            );
                            return 1;
                        })
                    )
                    .then(Commands.literal("toggle")
                        .executes(context -> {
                            // تبديل حالة التفعيل (تشغيل/إيقاف)
                            var data = MidnightAssistConfig.INSTANCE.getData();
                            data.setGlobalEnabled(!data.getGlobalEnabled());
                            MidnightAssistConfig.INSTANCE.save();

                            // إرسال رسالة الحالة الجديدة
                            String statusKey = data.getGlobalEnabled()
                                ? "chat.midnight-assist.enabled"
                                : "chat.midnight-assist.disabled";
                            MutableComponent prefixComp = Component.translatable("chat.midnight-assist.prefix");
                            MutableComponent status = Component.translatable(statusKey);
                            ((CommandSourceStack) context.getSource()).sendSuccess(
                                (Supplier<Component>) () -> prefixComp.copy().append(status), false
                            );
                            return 1;
                        })
                    )
                    .executes(context -> {
                        // عرض معلومات الاستخدام عند كتابة الأمر بدون残り
                        MutableComponent prefix = Component.translatable("chat.midnight-assist.prefix");
                        MutableComponent usage = Component.translatable("chat.midnight-assist.usage");
                        ((CommandSourceStack) context.getSource()).sendSuccess(
                            (Supplier<Component>) () -> prefix.copy().append(usage), false
                        );
                        return 1;
                    })
            );
        });
    }
}
