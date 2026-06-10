import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

const AVATAR_COLORS = [
  '#4f46e5', '#0ca678', '#e8590c', '#1c7ed6', '#9c36b5',
  '#c2255c', '#2b8a3e', '#e67700', '#3b5bdb', '#0b7285',
];

@Component({
  selector: 'app-avatar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      class="avatar"
      [style.width.px]="size()"
      [style.height.px]="size()"
      [style.fontSize.px]="size() * 0.4"
      [style.background]="color()"
      [attr.title]="name()"
    >{{ initials() }}</span>
  `,
  styles: `
    .avatar {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border-radius: var(--radius-full);
      color: #fff;
      font-weight: 600;
      line-height: 1;
      user-select: none;
      flex-shrink: 0;
    }
  `,
})
export class AvatarComponent {
  readonly name = input.required<string>();
  readonly size = input(40);

  protected readonly initials = computed(() => {
    const parts = this.name().trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) return '?';
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  });

  protected readonly color = computed(() => {
    const name = this.name();
    let hash = 0;
    for (let i = 0; i < name.length; i++) {
      hash = (hash << 5) - hash + name.charCodeAt(i);
      hash |= 0;
    }
    return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
  });
}
