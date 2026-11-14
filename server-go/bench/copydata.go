package bench

import (
	"database/sql"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// CopyData makes `to` a copy of the data folder `from`, for running bench-go beside the
// Node server without touching its data: the database as a consistent snapshot (VACUUM
// INTO, safe while the other server is running), the provider keys file, notes and
// attachments. Project folders and task worktrees are not copied - they are the same
// folders on disk, named by path in both databases.
func CopyData(from, to string, force bool) error {
	src := filepath.Join(from, ".bench", "bench.db")
	if !exists(src) {
		return fmt.Errorf("no Bench database at %s", src)
	}
	dst := filepath.Join(to, ".bench", "bench.db")
	if exists(dst) && !force {
		return fmt.Errorf("%s already exists; pass --force to replace it with a fresh copy", dst)
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	for _, suffix := range []string{"", "-wal", "-shm"} {
		os.Remove(dst + suffix)
	}
	db, err := sql.Open("sqlite3", "file:"+src+"?mode=ro&_busy_timeout=10000")
	if err != nil {
		return err
	}
	_, err = db.Exec("VACUUM INTO '" + strings.ReplaceAll(dst, "'", "''") + "'")
	db.Close()
	if err != nil {
		return fmt.Errorf("copying the database: %w", err)
	}
	if b, err := os.ReadFile(EnvFile(from)); err == nil {
		os.WriteFile(EnvFile(to), b, 0o600)
	}
	for _, dir := range []string{"notes", "attachments", "person-skills"} {
		copyTree(filepath.Join(from, ".bench", dir), filepath.Join(to, ".bench", dir))
	}
	// Which notes go into every session: kept by full path, so the global notes' paths
	// move to the copy. A project's notes live in the project and keep theirs.
	if b, err := os.ReadFile(filepath.Join(from, ".bench", "notes-included.json")); err == nil {
		oldNotes := filepath.Join(from, ".bench", "notes") + string(filepath.Separator)
		newNotes := filepath.Join(to, ".bench", "notes") + string(filepath.Separator)
		os.WriteFile(filepath.Join(to, ".bench", "notes-included.json"), []byte(strings.ReplaceAll(string(b), oldNotes, newNotes)), 0o644)
	}
	return nil
}

func copyTree(from, to string) {
	filepath.WalkDir(from, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		rel, _ := filepath.Rel(from, p)
		target := filepath.Join(to, rel)
		info, err := d.Info()
		if err != nil {
			return nil
		}
		switch {
		case d.IsDir():
			os.MkdirAll(target, info.Mode().Perm()|0o700)
		case info.Mode()&os.ModeSymlink != 0:
			if link, err := os.Readlink(p); err == nil {
				os.Symlink(link, target)
			}
		case info.Mode().IsRegular():
			in, err := os.Open(p)
			if err != nil {
				return nil
			}
			out, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, info.Mode().Perm())
			if err == nil {
				io.Copy(out, in)
				out.Close()
			}
			in.Close()
		}
		return nil
	})
}
