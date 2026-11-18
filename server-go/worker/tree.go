package worker

import (
	"archive/tar"
	"compress/gzip"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// unpackTree writes a gzipped tar tree under dir. Paths that climb out of dir are refused, so
// a tree can never write outside the worker's cache.
func unpackTree(gz []byte, dir string) error {
	zr, err := gzip.NewReader(strings.NewReader(string(gz)))
	if err != nil {
		return err
	}
	defer zr.Close()
	tr := tar.NewReader(zr)
	for {
		h, err := tr.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		name := filepath.Clean(h.Name)
		if name == "." {
			continue
		}
		if strings.HasPrefix(name, "..") || filepath.IsAbs(name) {
			return fmt.Errorf("the tree has an unsafe path %q", h.Name)
		}
		full := filepath.Join(dir, name)
		switch h.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(full, 0o755); err != nil {
				return err
			}
		case tar.TypeSymlink:
			// A symlink may point anywhere; it is kept only when it stays inside the tree.
			target := filepath.Clean(filepath.Join(filepath.Dir(name), h.Linkname))
			if strings.HasPrefix(target, "..") || filepath.IsAbs(h.Linkname) {
				continue
			}
			os.MkdirAll(filepath.Dir(full), 0o755)
			os.Remove(full)
			if err := os.Symlink(h.Linkname, full); err != nil {
				return err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
				return err
			}
			f, err := os.OpenFile(full, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, os.FileMode(h.Mode)&0o777)
			if err != nil {
				return err
			}
			if _, err := io.Copy(f, tr); err != nil {
				f.Close()
				return err
			}
			f.Close()
		}
	}
}
