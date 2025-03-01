// import api from '@/../frontend/mafia/src/api/axios';
import api from '@/api/axios';
import { AuthRequest, AuthResponse } from '@/types/auth';

export const authApi = {
  login: (data: AuthRequest) => api.post<AuthResponse>('/auth/login', data),
  register: (data: AuthRequest) => api.post<AuthResponse>('/auth/register', data),
  logout: () => api.post('/auth/logout'),
};

export default authApi;
