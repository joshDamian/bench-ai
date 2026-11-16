package bench

// The phone_* tools: how a session uses what the person's phone shares (devices.go). Each call
// goes to the phone and back; the phone checks the share again before it does anything.

import (
	"context"
	"fmt"
	"strings"
)

// phoneToolKind is the share kind each phone tool needs.
var phoneToolKind = map[string]string{
	"phone_messages": "messages", "phone_reply": "messages",
	"phone_events": "calendar", "phone_add_event": "calendar",
	"phone_contacts": "contacts", "phone_sms": "sms", "phone_handover": "handover",
	"phone_open": "use", "phone_notifications": "notifications",
}

// phoneHands are the tools that act on the app open on the hidden screen: they go to the phone
// the apps are on, which checks the screen still shows a shared app before acting.
var phoneHands = map[string]string{
	"phone_screen": "screen", "phone_tap": "tap", "phone_type": "type", "phone_swipe": "swipe",
	"phone_key": "key", "phone_done": "done",
}

var appProp = strProp("The app whose messages, e.g. WhatsApp; empty when only one app's messages are shared")

func phoneToolList() []any {
	tool := func(name, desc string, readOnly bool, schema *Obj) *Obj {
		return O("name", name, "description", desc, "inputSchema", schema,
			"annotations", O("readOnlyHint", readOnly, "destructiveHint", false), "execution", O("taskSupport", "forbidden"))
	}
	return []any{
		tool("phone_shares", "Lists what the person's phone shares (an app's messages, the calendar, contacts, texts, hand-overs) and whether it is online.", true, macSchema(O())),
		tool("phone_messages", "New messages from a shared app (WhatsApp, Telegram…), read from the phone's notifications: who, the chat, the text, when. Only what arrived while Bench was linked and is still recent.", true,
			macSchema(O("share", appProp, "chat", strProp("Optional: only this chat (a name)")))),
		tool("phone_reply", "Replies in a chat through the app's notification, without opening the app. Works while that chat has a message notification on the phone; otherwise use phone_handover.", false,
			macSchema(O("share", appProp, "chat", strProp("The chat, as phone_messages names it"), "text", strProp("The reply, as the person would write it")), "chat", "text")),
		tool("phone_events", "Events in the phone's calendar between two times.", true,
			macSchema(O("from", strProp("ISO date or date-time; default now"), "to", strProp("ISO date or date-time; default a week after from")))),
		tool("phone_add_event", "Adds an event to the phone's calendar.", false,
			macSchema(O("title", strProp("Title"), "start", strProp("ISO date-time, with offset"), "end", strProp("ISO date-time; default an hour after start"), "place", strProp("Optional: where"), "note", strProp("Optional: a note")), "title", "start")),
		tool("phone_contacts", "Looks up contacts on the phone by name: names and numbers.", true,
			macSchema(O("query", strProp("A name or part of one")), "query")),
		tool("phone_sms", "Sends a text message (SMS) from the phone.", false,
			macSchema(O("to", strProp("A phone number, with country code"), "text", strProp("The message")), "to", "text")),
		tool("phone_handover", "Prepares something in its app for the person to finish on the phone: a message (WhatsApp or SMS), an email, a calendar event, a route, a call, or a link. The phone shows it ready (or a notification to tap); the person sends or saves it.", false,
			macSchema(O(
				"what", O("type", "string", "enum", []any{"message", "email", "event", "route", "call", "link"}),
				"app", strProp("For a message: whatsapp (default) or sms"),
				"to", strProp("Number for a message or call (with country code); address for an email"),
				"text", strProp("Message or email body"), "subject", strProp("Email subject"),
				"title", strProp("Event title"), "start", strProp("Event start, ISO"), "end", strProp("Event end, ISO"),
				"place", strProp("Event place, or where a route goes"), "url", strProp("A link to open"),
				"label", strProp("One short line the person sees, e.g. 'Reply to the Jiji seller'"),
			), "what", "label")),
		tool("phone_open", "Opens a shared app on a hidden screen on the person's phone, so they keep using theirs, and returns what it shows. Then act with phone_tap, phone_type, phone_swipe and phone_key; finish with phone_done.", false,
			macSchema(O("app", strProp("The app, as phone_shares names it (e.g. inDrive)")), "app")),
		tool("phone_screen", "What the app on the hidden screen shows now: a screenshot, with its size in pixels.", true, macSchema(O())),
		tool("phone_tap", "Taps the hidden screen at x, y (pixels in the last screenshot). Answers with the screen after.", false,
			macSchema(O("x", O("type", "number"), "y", O("type", "number")), "x", "y")),
		tool("phone_type", "Types text into the focused field on the hidden screen (tap the field first). Answers with the screen after.", false,
			macSchema(O("text", strProp("The text"), "enter", O("type", "boolean", "description", "Press enter after")), "text")),
		tool("phone_swipe", "Swipes on the hidden screen from x1, y1 to x2, y2 (pixels), to scroll. Answers with the screen after.", false,
			macSchema(O("x1", O("type", "number"), "y1", O("type", "number"), "x2", O("type", "number"), "y2", O("type", "number")), "x1", "y1", "x2", "y2")),
		tool("phone_key", "Presses a key on the hidden screen: back, enter, or search. Answers with the screen after.", false,
			macSchema(O("key", O("type", "string", "enum", []any{"back", "enter", "search"})), "key")),
		tool("phone_confirm", "Asks the person before a step that pays, books, orders, sends, posts or deletes, with what it is. Call it, wait for the answer, and only then take the step.", false,
			macSchema(O("what", strProp("The step, as a question: 'Book this ride on inDrive?'"), "detail", strProp("What it is: the amount, who, when")), "what", "detail")),
		tool("phone_done", "Closes the hidden screen and the app on it. Call it when the task in the app is finished.", false, macSchema(O())),
		tool("phone_notifications", "Recent notifications from the apps shared to use (a driver arriving, a seller's message): app, title, text, when.", true,
			macSchema(O("app", strProp("Optional: only this app")))),
		tool("phone_ask", "Asks the person's phone to share something more. They approve or decline it on the phone; carry on meanwhile.", false,
			macSchema(O("kind", O("type", "string", "enum", []any{"calendar", "contacts", "sms", "handover", "use", "notifications"}),
				"app", strProp("For use: the app, e.g. inDrive"),
				"access", O("type", "string", "enum", []any{"read", "reply", "write"}, "description", "messages: read or reply; calendar: read or write"),
				"why", strProp("One line on why, shown on the phone")), "kind", "why")),
	}
}

func phoneTools(svc *Service, sessionID string) map[string]func(context.Context, *Obj) *Obj {
	d := svc.Devices()
	call := func(tool string, need func(sh *Obj) error) func(context.Context, *Obj) *Obj {
		return func(ctx context.Context, a *Obj) *Obj {
			dv, sh, err := d.shareOf(phoneToolKind["phone_"+tool], a.Str("share"))
			if err != nil {
				return toolErr(err.Error())
			}
			if need != nil {
				if err := need(sh); err != nil {
					return toolErr(err.Error())
				}
			}
			r, err := d.call(ctx, dv, sh, tool, a.Clone().Set("session_id", sessionID))
			if err != nil {
				return toolErr(err.Error())
			}
			return phoneResult(r)
		}
	}
	// The hands go to the phone with apps shared to use; the phone knows which one is open.
	hands := func(tool string) func(context.Context, *Obj) *Obj {
		return func(ctx context.Context, a *Obj) *Obj {
			dv, sh, err := d.shareOf("use", "")
			if err != nil && !strings.Contains(err.Error(), "More than one") {
				return toolErr("No app on the phone is shared to use. phone_ask asks for one")
			}
			if dv == nil {
				dv, sh = d.anyShare("use")
			}
			r, err := d.call(ctx, dv, sh, tool, a.Clone().Set("session_id", sessionID))
			if err != nil {
				return toolErr(err.Error())
			}
			return phoneResult(r)
		}
	}
	return map[string]func(context.Context, *Obj) *Obj{
		"phone_shares": toolFn(func(ctx context.Context, a *Obj) (string, error) {
			if n := d.Note(); n != "" {
				return n, nil
			}
			return "No phone has linked yet. The person links it from Bench for Android: Settings, This phone.", nil
		}),
		"phone_messages": call("messages", nil),
		"phone_reply": call("reply", func(sh *Obj) error {
			if sh.Str("access") != "reply" {
				return fmt.Errorf("%s is shared to read only. Use phone_handover to prepare the reply, or phone_ask for reply access", sh.Str("name"))
			}
			return nil
		}),
		"phone_events": call("events", nil),
		"phone_add_event": call("add_event", func(sh *Obj) error {
			if sh.Str("access") != "write" {
				return fmt.Errorf("The calendar is shared to read only. Use phone_handover with what: event, or phone_ask for write access")
			}
			return nil
		}),
		"phone_contacts": call("contacts", nil),
		"phone_sms":      call("sms", nil),
		"phone_handover": call("handover", nil),
		"phone_open": func(ctx context.Context, a *Obj) *Obj {
			return call("open", nil)(ctx, a.Clone().Set("share", a.Str("app")))
		},
		"phone_notifications": call("notifications", nil),
		"phone_screen":        hands("screen"),
		"phone_tap":           hands("tap"),
		"phone_type":          hands("type"),
		"phone_swipe":         hands("swipe"),
		"phone_key":           hands("key"),
		"phone_done":          hands("done"),
		// The approval is the point: by the time this runs, the person said yes.
		"phone_confirm": toolFn(func(ctx context.Context, a *Obj) (string, error) {
			return "The person said yes. Take the step now.", nil
		}),
		"phone_ask": toolFn(func(ctx context.Context, a *Obj) (string, error) {
			var dev *Obj
			for _, x := range d.List() {
				if x.Str("kind") == "phone" {
					if dev != nil {
						return "", fmt.Errorf("More than one phone is linked")
					}
					dev = x
				}
			}
			if dev == nil {
				return "", fmt.Errorf("No phone has linked yet")
			}
			ask, err := d.Ask(dev.Str("id"), O("kind", a.Str("kind"), "path", strings.TrimSpace(a.Str("app")), "access", a.Str("access"), "why", a.Str("why")), sessionID)
			if err != nil {
				return "", err
			}
			where := "It is on the phone now."
			if !truthy(dev.Get("online")) {
				where = dev.Str("name") + " is offline; the request waits there."
			}
			return fmt.Sprintf("Asked %s for %s. %s The person approves or declines it on the phone; you are told here when they do. Carry on with other steps.", dev.Str("name"), askLabel(ask), where), nil
		}),
	}
}

// phoneResult is a phone's answer as a tool result: its text, and the screen when it sent one.
func phoneResult(r *Obj) *Obj {
	img := r.Str("image")
	if img == "" {
		return toolOK(r.Str("text"))
	}
	content := []any{O("type", "image", "data", img, "mimeType", "image/jpeg")}
	if t := r.Str("text"); t != "" {
		content = append(content, O("type", "text", "text", t))
	}
	return O("content", content)
}
