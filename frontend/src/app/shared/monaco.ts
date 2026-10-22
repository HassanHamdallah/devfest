import {
  Component,
  ElementRef,
  ViewChild,
  AfterViewInit,
  OnDestroy,
  input,
  output,
  effect,
  signal,
} from "@angular/core";
import type * as Monaco from "monaco-editor";
interface Amd {
  (names: string[], ok: () => void, fail: (e: unknown) => void): void;
  config(options: unknown): void;
}
declare global {
  interface Window {
    require?: Amd;
    monaco?: typeof Monaco;
  }
}
let loading: Promise<void> | null = null;
function load(): Promise<void> {
  if (!loading)
    loading = new Promise((resolve, reject) => {
      const start = () => {
        window.require!.config({ paths: { vs: "/monaco/vs" } });
        window.require!(["vs/editor/editor.main"], resolve, reject);
      };
      if (window.require) start();
      else {
        const script = document.createElement("script");
        script.src = "/monaco/vs/loader.js";
        script.onload = start;
        script.onerror = () =>
          reject(new Error("Editor could not load. Refresh to retry."));
        document.head.appendChild(script);
      }
    });
  return loading;
}
@Component({
  selector: "code-editor",
  template:
    '<div #host class="editor-host" aria-label="Source code editor"></div>@if(error()){<p role="alert">{{error()}}</p>}',
  styles: [":host{display:block}.editor-host{height:460px;min-height:280px}"],
})
export class CodeEditor implements AfterViewInit, OnDestroy {
  @ViewChild("host", { static: true }) host!: ElementRef<HTMLDivElement>;
  source = input("");
  language = input("python");
  changed = output<string>();
  error = signal("");
  private editor?: Monaco.editor.IStandaloneCodeEditor;
  private destroyed = false;
  constructor() {
    effect(() => {
      const source = this.source(),
        language = this.language();
      if (this.editor) {
        if (this.editor.getValue() !== source) this.editor.setValue(source);
        const model = this.editor.getModel();
        if (model) window.monaco!.editor.setModelLanguage(model, language);
      }
    });
  }
  async ngAfterViewInit() {
    try {
      await load();
      if (this.destroyed) return;
      this.editor = window.monaco!.editor.create(this.host.nativeElement, {
        value: this.source(),
        language: this.language(),
        theme: "vs-dark",
        automaticLayout: true,
        minimap: { enabled: false },
        fontSize: 14,
        tabSize: 4,
        scrollBeyondLastLine: false,
        ariaLabel: "Program source code",
      });
      this.editor.onDidChangeModelContent(() =>
        this.changed.emit(this.editor!.getValue()),
      );
    } catch {
      this.error.set("Editor could not load. Refresh to retry.");
    }
  }
  ngOnDestroy() {
    this.destroyed = true;
    const model = this.editor?.getModel();
    this.editor?.dispose();
    model?.dispose();
  }
}
