export interface InitiativeEntry {
  name: string;
  value: number;
  isPlayer: boolean;
  playerId?: string;
}

export class InitiativeTracker {
  private entries: InitiativeEntry[] = [];
  private currentIndex = -1;
  active = false;

  add(name: string, value: number, isPlayer: boolean, playerId?: string): void {
    this.entries = this.entries.filter(e => e.name.toLowerCase() !== name.toLowerCase());
    this.entries.push({ name, value, isPlayer, playerId });
    this.entries.sort((a, b) => b.value - a.value);
    if (this.currentIndex >= this.entries.length) {
      this.currentIndex = this.entries.length - 1;
    }
  }

  remove(name: string): boolean {
    const idx = this.entries.findIndex(e => e.name.toLowerCase() === name.toLowerCase());
    if (idx === -1) return false;
    this.entries.splice(idx, 1);
    if (this.currentIndex >= this.entries.length) {
      this.currentIndex = Math.max(0, this.entries.length - 1);
    }
    return true;
  }

  start(): InitiativeEntry | null {
    if (this.entries.length === 0) return null;
    this.currentIndex = 0;
    this.active = true;
    return this.entries[0];
  }

  next(): InitiativeEntry | null {
    if (this.entries.length === 0) return null;
    this.currentIndex = (this.currentIndex + 1) % this.entries.length;
    return this.entries[this.currentIndex];
  }

  current(): InitiativeEntry | null {
    if (this.entries.length === 0 || this.currentIndex < 0) return null;
    return this.entries[this.currentIndex];
  }

  clear(): void {
    this.entries = [];
    this.currentIndex = -1;
    this.active = false;
  }

  formatList(): string {
    if (this.entries.length === 0) return '§c[Initiative] Tracker is empty.§r';
    let out = '§6§l=== Initiative Order ===§r\n';
    for (let i = 0; i < this.entries.length; i++) {
      const e = this.entries[i];
      const arrow = i === this.currentIndex ? '§a► §r' : '  ';
      const type = e.isPlayer ? '§b[P]§r' : '§c[M]§r';
      out += `${arrow}${type} §e${e.name}§r: §f${e.value}\n`;
    }
    return out.trim();
  }
}
