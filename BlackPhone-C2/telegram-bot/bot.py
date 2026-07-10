import json, os
from telegram import Update, InlineKeyboardButton, InlineKeyboardMarkup
from telegram.ext import Application, CommandHandler, CallbackQueryHandler, MessageHandler, filters, ContextTypes

BOT_TOKEN = "YOUR_BOT_TOKEN"
ADMIN_ID = "YOUR_CHAT_ID"
DEVICES_FILE = "devices.json"

def load(): 
    if os.path.exists(DEVICES_FILE):
        with open(DEVICES_FILE) as f: return json.load(f)
    return {}
def save(d): 
    with open(DEVICES_FILE, 'w') as f: json.dump(d, f, indent=4)

active = {}

async def start(update, ctx):
    await update.message.reply_text("👽 /devices لعرض الأجهزة")

async def handle_reg(update, ctx):
    t = update.message.text
    if not t.startswith("REGISTER:"): return
    _, data = t.split(":", 1)
    parts = data.split("|")
    if len(parts) < 2: return
    d_id, name = parts[0], parts[1]
    devs = load()
    devs[d_id] = {"name": name, "chat": update.effective_chat.id}
    save(devs)
    await ctx.bot.send_message(ADMIN_ID, f"✅ جديد: {name}\n🆔: `{d_id}`")

async def devices_cmd(update, ctx):
    devs = load()
    if not devs: return await update.message.reply_text("لا توجد أجهزة.")
    kb = [[InlineKeyboardButton(f"📱 {d['name']}", callback_data=f"sel_{id}")] for id, d in devs.items()]
    await update.message.reply_text("اختر جهاز:", reply_markup=InlineKeyboardMarkup(kb))

async def btn(update, ctx):
    q = update.callback_query; await q.answer()
    d_id = q.data.replace("sel_", "")
    devs = load()
    if d_id not in devs: return await q.edit_message_text("❌ غير موجود")
    active[q.from_user.id] = d_id
    await q.edit_message_text(f"✅ تم اختيار: {devs[d_id]['name']}")

async def fwd(update, ctx):
    uid = update.effective_user.id
    if uid not in active: return await update.message.reply_text("⚠️ اختر جهازاً بـ /devices")
    d_id = active[uid]; devs = load()
    if d_id not in devs: return
    chat = devs[d_id].get("chat")
    if chat:
        await ctx.bot.send_message(chat, f"CMD:{update.message.text}")
        await update.message.reply_text("✅ أُرسل")

app = Application.builder().token(BOT_TOKEN).build()
app.add_handler(CommandHandler("start", start))
app.add_handler(CommandHandler("devices", devices_cmd))
app.add_handler(CallbackQueryHandler(btn))
app.add_handler(MessageHandler(filters.Regex(r'^REGISTER:'), handle_reg))
app.add_handler(MessageHandler(filters.COMMAND, fwd))
app.run_polling()
