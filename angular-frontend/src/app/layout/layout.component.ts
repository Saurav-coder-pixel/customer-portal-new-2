import { Component } from '@angular/core';
import { Router } from '@angular/router';

@Component({
  selector: 'app-layout',
  standalone: false,
  templateUrl: './layout.component.html',
  styleUrls: ['./layout.component.css']
})
export class LayoutComponent {
  isDrawerOpen = false;

  constructor(private router: Router) {}

  toggleDrawer(): void {
    this.isDrawerOpen = !this.isDrawerOpen;
  }

  navigate(path: string): void {
    this.router.navigate([path]);
  }

  isActive(path: string): boolean {
    return this.router.url === '/' + path || this.router.url.startsWith('/' + path);
  }
}