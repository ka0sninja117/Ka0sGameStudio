export interface DiceResult {
  rolls: number[];
  modifier: number;
  total: number;
  notation: string;
  advantage?: 'advantage' | 'disadvantage';
}

export function parseDiceNotation(input: string): DiceResult | null {
  let notation = input.toLowerCase().trim();
  let advantageMode: 'advantage' | 'disadvantage' | undefined;

  if (/\b(adv|advantage)\b/.test(notation)) {
    advantageMode = 'advantage';
    notation = notation.replace(/\s*(adv|advantage)\s*$/, '').trim();
  } else if (/\b(dis|disadvantage)\b/.test(notation)) {
    advantageMode = 'disadvantage';
    notation = notation.replace(/\s*(dis|disadvantage)\s*$/, '').trim();
  }

  const match = notation.match(/^(\d*)d(\d+)([+-]\d+)?$/);
  if (!match) return null;

  const count = parseInt(match[1] || '1');
  const sides = parseInt(match[2]);
  const modifier = parseInt(match[3] || '0');

  if (count < 1 || count > 100 || sides < 2 || sides > 1000) return null;

  const rolls: number[] = [];

  if (advantageMode && count === 1) {
    const r1 = Math.floor(Math.random() * sides) + 1;
    const r2 = Math.floor(Math.random() * sides) + 1;
    rolls.push(r1, r2);
    const kept = advantageMode === 'advantage' ? Math.max(r1, r2) : Math.min(r1, r2);
    return { rolls, modifier, total: kept + modifier, notation: input.trim(), advantage: advantageMode };
  }

  for (let i = 0; i < count; i++) {
    rolls.push(Math.floor(Math.random() * sides) + 1);
  }

  const sum = rolls.reduce((a, b) => a + b, 0);
  return { rolls, modifier, total: sum + modifier, notation: input.trim() };
}

export function formatDiceResult(result: DiceResult, playerName: string): string {
  const isAdvDis = result.advantage !== undefined;
  const rollStr = isAdvDis
    ? `[${result.rolls[0]}, ${result.rolls[1]}]`
    : result.rolls.length === 1
      ? `${result.rolls[0]}`
      : `[${result.rolls.join(', ')}]`;

  let modStr = '';
  if (result.modifier > 0) modStr = ` + ${result.modifier}`;
  else if (result.modifier < 0) modStr = ` - ${Math.abs(result.modifier)}`;

  let advStr = '';
  if (result.advantage === 'advantage') advStr = ' §d(Advantage)§r';
  else if (result.advantage === 'disadvantage') advStr = ' §5(Disadvantage)§r';

  // Highlight natural 20 on a d20 (single die, roll equals 20 before modifier)
  const isNat20 = result.rolls.length <= 2 && result.rolls.some(r => r === 20);
  const totalColor = isNat20 ? '§a§l' : '§e§l';

  return `§b${playerName}§r rolled §7${result.notation}§r${advStr}: ${rollStr}${modStr} = ${totalColor}${result.total}§r`;
}
