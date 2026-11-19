package bench

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func bodyText(t *testing.T, resp *http.Response) string {
	t.Helper()
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

// linkWorker enrols a worker that offers the given kinds and links it.
func linkWorker(t *testing.T, ts *httptest.Server, svc *Service, name string, kinds []any) (*Obj, *fakeWorker) {
	t.Helper()
	enroled, token := enrolWorker(t, ts.URL, O("name", name, "platform", "darwin", "slots", 1))
	fw := newFakeWorker(t, ts.URL, token)
	fw.hello(name, kinds, nil, 1, 8)
	waitFor(t, func() bool {
		wk := svc.Workers().Worker(enroled.Str("id"))
		return wk != nil && truthy(wk.Get("online")) && wk.Str("version") == "0.2"
	})
	return enroled, fw
}

// A worker's service is listed while it holds it, and gone when it says it stopped.
func TestAWorkerServiceIsListedAndDropped(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	_, fw := linkWorker(t, ts, svc, "Mac", []any{"exec", "ios-sim"})

	fw.send(O("type", "service", "id", "SIM-1", "kind", "ios-sim", "state", "booted",
		"detail", O("device", "iPhone 17 Pro")))
	waitFor(t, func() bool { return len(svc.Workers().Services()) == 1 })
	got := svc.Workers().Services()[0]
	if got.Str("id") != "SIM-1" || got.Str("worker") != "Mac" || got.Str("state") != "booted" ||
		got.ObjAt("detail").Str("device") != "iPhone 17 Pro" {
		t.Fatalf("service %s", ToJSON(got))
	}
	wk := svc.Workers().List()[0]
	if held := arr(wk.Get("services")); len(held) != 1 {
		t.Fatalf("worker services %v", held)
	}
	// The worker says it shut the simulator down.
	fw.send(O("type", "service", "id", "SIM-1", "kind", "ios-sim", "state", "stopped"))
	waitFor(t, func() bool { return len(svc.Workers().Services()) == 0 })
}

// The worker going takes its services with it: nothing is left pointing at a dead machine.
func TestAServiceGoesWhenItsWorkerDoes(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	_, fw := linkWorker(t, ts, svc, "Mac", []any{"ios-sim"})
	fw.send(O("type", "service", "id", "SIM-1", "kind", "ios-sim", "state", "booted"))
	waitFor(t, func() bool { return len(svc.Workers().Services()) == 1 })

	fw.conn.Close(200, "")
	waitFor(t, func() bool { return len(svc.Workers().Services()) == 0 })
	if _, err := svc.Workers().StopService("SIM-1"); err == nil {
		t.Fatal("a service of a gone worker was still stoppable")
	}
}

// Stopping a service asks the worker to end it and forgets it at once.
func TestStopServiceTellsTheWorker(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	_, fw := linkWorker(t, ts, svc, "Mac", []any{"ios-sim"})
	fw.send(O("type", "service", "id", "SIM-1", "kind", "ios-sim", "state", "booted"))
	waitFor(t, func() bool { return len(svc.Workers().Services()) == 1 })

	if _, err := svc.Workers().StopService("SIM-1"); err != nil {
		t.Fatal(err)
	}
	msg := fw.read()
	if msg.Str("type") != "service_stop" || msg.Str("id") != "SIM-1" || msg.Str("kind") != "ios-sim" {
		t.Fatalf("worker told %s", ToJSON(msg))
	}
	if len(svc.Workers().Services()) != 0 {
		t.Fatal("a stopped service is still listed")
	}
}

// Starting a service runs its action on the worker that will hold it, and returns the
// service only once the worker has registered it.
func TestStartServiceRunsOnTheWorkerThatHoldsIt(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	mac, fw := linkWorker(t, ts, svc, "Mac", []any{"exec", "ios-sim"})
	_, _ = linkWorker(t, ts, svc, "Other", []any{"exec"})

	type answer struct {
		sv  *Obj
		err error
	}
	got := make(chan answer, 1)
	go func() {
		sv, err := svc.StartService(context.Background(), mac.Str("id"), "ios-sim", O("device", "iPhone 17 Pro"))
		got <- answer{sv, err}
	}()
	job := fw.read()
	if job.Str("type") != "job" || job.Str("kind") != "ios-sim" ||
		job.ObjAt("args").Str("action") != "boot" || job.ObjAt("args").Str("device") != "iPhone 17 Pro" {
		t.Fatalf("job %s", ToJSON(job))
	}
	// The worker boots it, then registers it, then says the job is done.
	fw.send(O("type", "service", "id", "SIM-9", "kind", "ios-sim", "state", "booted",
		"detail", O("device", "iPhone 17 Pro")))
	fw.send(O("type", "job_done", "id", job.Str("id"), "ok", true, "text", "Booted", "ranMs", 900))

	select {
	case a := <-got:
		if a.err != nil {
			t.Fatal(a.err)
		}
		if a.sv.Str("id") != "SIM-9" || a.sv.Str("worker") != "Mac" || a.sv.Str("state") != "booted" {
			t.Fatalf("service %s", ToJSON(a.sv))
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the start did not come back")
	}
}

// A worker that is not linked cannot be told to start anything.
func TestStartServiceNeedsAnOnlineWorker(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	enroled, _ := enrolWorker(t, ts.URL, O("name", "Sleeping Mac", "platform", "darwin"))
	_, err := svc.StartService(context.Background(), enroled.Str("id"), "ios-sim", nil)
	if err == nil {
		t.Fatal("an offline worker started a service")
	}
}

// The routes: a person lists services and may stop one; a worker may not.
func TestServiceRoutes(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	_, fw := linkWorker(t, ts, svc, "Mac", []any{"ios-sim"})
	fw.send(O("type", "service", "id", "SIM-1", "kind", "ios-sim", "state", "booted"))
	waitFor(t, func() bool { return len(svc.Workers().Services()) == 1 })

	resp, err := http.Get(ts.URL + "/api/services")
	if err != nil {
		t.Fatal(err)
	}
	raw := bodyText(t, resp)
	list, _ := ParseJSON(raw).([]any)
	if len(list) != 1 || list[0].(*Obj).Str("id") != "SIM-1" {
		t.Fatalf("GET /api/services %s", raw)
	}

	resp, err = http.Post(ts.URL+"/api/services/SIM-1/stop", "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	bodyText(t, resp)
	if resp.StatusCode != 200 {
		t.Fatalf("stop %d", resp.StatusCode)
	}
	if msg := fw.read(); msg.Str("type") != "service_stop" {
		t.Fatalf("worker told %s", ToJSON(msg))
	}
	if _, err := svc.Workers().StopService("SIM-1"); err == nil {
		t.Fatal("stopping a service twice was allowed")
	}
}
