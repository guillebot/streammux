package main

import (
	"flag"
	"fmt"
	"os"
	"strings"

	"gitlab.com/dmr4013905/techarchitecture/techarchitecture/streammux/internal/auth"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "token":
		runToken(os.Args[2:])
	default:
		usage()
		os.Exit(2)
	}
}

func runToken(args []string) {
	if len(args) < 1 {
		usage()
		os.Exit(2)
	}
	dbPath := env("MCP_TOKEN_DB_PATH", "/data/tokens.db")
	store, err := auth.Open(dbPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "open token db: %v\n", err)
		os.Exit(1)
	}
	defer store.Close()

	switch args[0] {
	case "create":
		fs := flag.NewFlagSet("create", flag.ExitOnError)
		name := fs.String("name", "", "token display name")
		scopes := fs.String("scopes", "mcp,docs,read,write,admin", "comma-separated scopes")
		role := fs.String("role", "ADMIN", "role cap")
		_ = fs.Parse(args[1:])
		if *name == "" {
			fmt.Fprintln(os.Stderr, "--name required")
			os.Exit(2)
		}
		plain, rec, err := store.Create(*name, splitCSV(*scopes), *role)
		if err != nil {
			fmt.Fprintf(os.Stderr, "create: %v\n", err)
			os.Exit(1)
		}
		fmt.Printf("token=%s\nprefix=%s\nid=%d\n", plain, rec.DisplayPrefix, rec.ID)
	case "list":
		recs, err := store.List()
		if err != nil {
			fmt.Fprintf(os.Stderr, "list: %v\n", err)
			os.Exit(1)
		}
		for _, r := range recs {
			fmt.Printf("%d\t%s\t%s\t%s\t%v\n", r.ID, r.DisplayPrefix, r.Name, r.Role, r.Scopes)
		}
	case "revoke":
		fs := flag.NewFlagSet("revoke", flag.ExitOnError)
		id := fs.Int64("id", 0, "token id")
		_ = fs.Parse(args[1:])
		if *id == 0 {
			fmt.Fprintln(os.Stderr, "--id required")
			os.Exit(2)
		}
		if err := store.Revoke(*id); err != nil {
			fmt.Fprintf(os.Stderr, "revoke: %v\n", err)
			os.Exit(1)
		}
		fmt.Println("revoked")
	default:
		usage()
		os.Exit(2)
	}
}

func splitCSV(s string) []string {
	parts := strings.Split(s, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

func env(key, fallback string) string {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return fallback
	}
	return v
}

func usage() {
	fmt.Fprintf(os.Stderr, `Usage:
  stmctl token create --name NAME [--scopes mcp,docs,read,write,admin] [--role ADMIN]
  stmctl token list
  stmctl token revoke --id ID

Env: MCP_TOKEN_DB_PATH (default /data/tokens.db)
`)
}
