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
		tool("phone_ask", "Asks the person's phone to share something more. They approve or decline it on the phone; carry on meanwhile.", false,
			macSchema(O("kind", O("type", "string", "enum", []any{"messages", "calendar", "contacts", "sms", "handover"}),
				"app", strProp("For messages: the app, e.g. WhatsApp"),
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
			r, err := d.call(ctx, dv, sh, tool, a)
			if err != nil {
				return toolErr(err.Error())
			}
			return toolOK(r.Str("text"))
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
