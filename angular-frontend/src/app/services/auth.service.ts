import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  username: string;
  password: string;
  confirmPassword: string;
}

export interface AuthUserData {
  userId: number;
  email: string;
  username: string;
}

export interface AuthResponse {
  success: boolean;
  message: string;
  data?: AuthUserData;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly API = '/api/auth';

  constructor(private http: HttpClient) {}

  login(req: LoginRequest): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(`${this.API}/login`, req);
  }

  register(req: RegisterRequest): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(`${this.API}/register`, req);
  }
}