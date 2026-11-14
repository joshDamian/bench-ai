// bench-go: the Bench server, in Go. Same API, same data folder layout, same apps.
//
//	bench-go serve        run the server (BENCH_ROOT, PORT, BENCH_HOST as for `bench serve`)
//	bench-go copy-data --from <data folder> --to <data folder> [--force]
//	                      a snapshot of a Bench data folder, for running beside the Node server
//	bench-go version
package main

import (
	"fmt"
	"net/http"
	_ "net/http/pprof"
	"os"
	"os/signal"
	"syscall"

	"bench/server-go/bench"
)

var commit = "dev"

func main() {
	cmd := "serve"
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}
	switch cmd {
	case "serve":
		serve()
	case "copy-data":
		from, to, force := "", "", false
		for i := 2; i < len(os.Args); i++ {
			switch os.Args[i] {
			case "--from":
				i++
				from = os.Args[i]
			case "--to":
				i++
				to = os.Args[i]
			case "--force":
				force = true
			}
		}
		if from == "" || to == "" {
			fmt.Fprintln(os.Stderr, "usage: bench-go copy-data --from <data folder> --to <data folder> [--force]")
			os.Exit(2)
		}
		if err := bench.CopyData(from, to, force); err != nil {
			fmt.Fprintln(os.Stderr, "bench-go:", err)
			os.Exit(1)
		}
		fmt.Printf("Copied %s to %s\n", from, to)
	case "version", "--version", "-v":
		fmt.Printf("bench-go %s (protocol of agent SDK %s)\n", commit, bench.SDKVersion)
	default:
		fmt.Fprintf(os.Stderr, "usage: bench-go [serve|version]\n")
		os.Exit(2)
	}
}

func serve() {
	root := bench.Env("ROOT")
	if root == "" {
		root, _ = os.Getwd()
	}
	bench.LoadEnvFile(root)
	svc, err := bench.NewService(root)
	if err != nil {
		fmt.Fprintln(os.Stderr, "bench-go:", err)
		os.Exit(1)
	}
	if addr := bench.Env("PPROF"); addr != "" {
		go http.ListenAndServe(addr, nil)
	}
	srv := bench.NewServer(svc, root)
	if err := srv.Listen(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	select {
	case <-sig:
		srv.Shutdown()
	case <-srv.Done:
	}
}
